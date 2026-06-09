package mockserver

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// Client is a MockServer control-plane client.
type Client struct {
	baseURL    string
	httpClient *http.Client
}

// Option configures a Client.
type Option func(*Client)

// WithHTTPClient sets a custom http.Client for the MockServer client.
func WithHTTPClient(c *http.Client) Option {
	return func(client *Client) {
		client.httpClient = c
	}
}

// WithTimeout sets the HTTP timeout for the MockServer client.
func WithTimeout(d time.Duration) Option {
	return func(client *Client) {
		client.httpClient.Timeout = d
	}
}

// WithContextPath sets a context path prefix (e.g., "/mockserver-proxy").
func WithContextPath(path string) Option {
	return func(client *Client) {
		client.baseURL = client.baseURL + path
	}
}

// WithTLS configures the client to use HTTPS.
func WithTLS() Option {
	return func(client *Client) {
		u, _ := url.Parse(client.baseURL)
		u.Scheme = "https"
		client.baseURL = u.String()
	}
}

// New creates a new MockServer client.
//
// Example:
//
//	client := mockserver.New("localhost", 1080)
func New(host string, port int, opts ...Option) *Client {
	c := &Client{
		baseURL: fmt.Sprintf("http://%s:%d", host, port),
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
	for _, opt := range opts {
		opt(c)
	}
	return c
}

// NewFromURL creates a new MockServer client from a full base URL.
//
// Example:
//
//	client := mockserver.NewFromURL("http://mockserver.internal:1080")
func NewFromURL(baseURL string, opts ...Option) *Client {
	c := &Client{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
	for _, opt := range opts {
		opt(c)
	}
	return c
}

// ForwardChainExpectation allows chaining a When() call with Respond/Forward/Error.
type ForwardChainExpectation struct {
	client      *Client
	expectation Expectation
}

// WithID sets a custom expectation ID for deduplication/update.
func (f *ForwardChainExpectation) WithID(id string) *ForwardChainExpectation {
	f.expectation.ID = id
	return f
}

// WithPriority sets the expectation priority.
func (f *ForwardChainExpectation) WithPriority(priority int) *ForwardChainExpectation {
	f.expectation.Priority = priority
	return f
}

// Respond completes the expectation with an HTTP response action.
func (f *ForwardChainExpectation) Respond(rb *ResponseBuilder) ([]Expectation, error) {
	resp := rb.Build()
	f.expectation.HttpResponse = &resp
	return f.client.Upsert(f.expectation)
}

// Forward completes the expectation with a forward action.
func (f *ForwardChainExpectation) Forward(fb *ForwardBuilder) ([]Expectation, error) {
	fwd := fb.Build()
	f.expectation.HttpForward = &fwd
	return f.client.Upsert(f.expectation)
}

// RespondWithError completes the expectation with an error action.
func (f *ForwardChainExpectation) RespondWithError(eb *ErrorBuilder) ([]Expectation, error) {
	e := eb.Build()
	f.expectation.HttpError = &e
	return f.client.Upsert(f.expectation)
}

// When begins building an expectation with a fluent API.
func (c *Client) When(rb *RequestBuilder, opts ...ExpectationOption) *ForwardChainExpectation {
	req := rb.Build()
	exp := Expectation{
		HttpRequest: &req,
	}
	for _, opt := range opts {
		opt(&exp)
	}
	return &ForwardChainExpectation{client: c, expectation: exp}
}

// ExpectationOption configures an expectation created via When().
type ExpectationOption func(*Expectation)

// WithTimes sets the Times on an expectation created via When().
func WithTimes(t *Times) ExpectationOption {
	return func(e *Expectation) {
		e.Times = t
	}
}

// WithTimeToLive sets the TimeToLive on an expectation created via When().
func WithTimeToLive(ttl *TimeToLive) ExpectationOption {
	return func(e *Expectation) {
		e.TimeToLive = ttl
	}
}

// Upsert creates or updates one or more expectations.
func (c *Client) Upsert(expectations ...Expectation) ([]Expectation, error) {
	body, err := json.Marshal(expectations)
	if err != nil {
		return nil, fmt.Errorf("mockserver: marshal expectations: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/expectation", body, nil)
	if err != nil {
		return nil, err
	}

	if statusCode == 400 || statusCode == 406 {
		return nil, fmt.Errorf("mockserver: invalid expectation (status %d): %s", statusCode, string(respBody))
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: create expectation failed (status %d): %s", statusCode, string(respBody))
	}

	var created []Expectation
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &created); err != nil {
			// Not all versions return JSON; fall back to returning what was sent.
			return expectations, nil
		}
	}
	return created, nil
}

// VerificationError is returned when a verification fails (HTTP 406).
type VerificationError struct {
	Message string
}

func (e *VerificationError) Error() string {
	return fmt.Sprintf("mockserver: verification failed: %s", e.Message)
}

// Verify asserts that a request matching the given builder was received
// the specified number of times. Pass nil for times to use the server default.
func (c *Client) Verify(rb *RequestBuilder, times *VerificationTimes) error {
	req := rb.Build()
	v := verification{
		HttpRequest: &req,
		Times:       times,
	}

	body, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("mockserver: marshal verification: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/verify", body, nil)
	if err != nil {
		return err
	}

	if statusCode == 406 {
		return &VerificationError{Message: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: verify failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// VerifySequence asserts that requests matching the given builders were received
// in the specified order.
func (c *Client) VerifySequence(builders ...*RequestBuilder) error {
	requests := make([]HttpRequest, len(builders))
	for i, b := range builders {
		requests[i] = b.Build()
	}
	v := verificationSequence{HttpRequests: requests}

	body, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("mockserver: marshal verification sequence: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/verifySequence", body, nil)
	if err != nil {
		return err
	}

	if statusCode == 406 {
		return &VerificationError{Message: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: verify sequence failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// ClearType specifies what to clear.
type ClearType string

const (
	ClearAll          ClearType = "all"
	ClearLog          ClearType = "log"
	ClearExpectations ClearType = "expectations"
)

// Clear removes expectations and/or recorded requests matching the given request.
// If rb is nil, all are cleared. clearType defaults to "all" if empty.
func (c *Client) Clear(rb *RequestBuilder, clearType ClearType) error {
	var body []byte
	var err error
	if rb != nil {
		req := rb.Build()
		body, err = json.Marshal(req)
		if err != nil {
			return fmt.Errorf("mockserver: marshal clear request: %w", err)
		}
	}

	params := make(url.Values)
	if clearType != "" {
		params.Set("type", string(clearType))
	}

	_, statusCode, err := c.doRequest("PUT", "/mockserver/clear", body, params)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear failed (status %d)", statusCode)
	}
	return nil
}

// ClearByID removes the expectation with the given ID.
func (c *Client) ClearByID(expectationID string, clearType ClearType) error {
	payload := map[string]string{"id": expectationID}
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("mockserver: marshal clear by id: %w", err)
	}

	params := make(url.Values)
	if clearType != "" {
		params.Set("type", string(clearType))
	}

	_, statusCode, err := c.doRequest("PUT", "/mockserver/clear", body, params)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear by id failed (status %d)", statusCode)
	}
	return nil
}

// Reset clears all expectations, recorded requests, and logs.
func (c *Client) Reset() error {
	_, statusCode, err := c.doRequest("PUT", "/mockserver/reset", nil, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: reset failed (status %d)", statusCode)
	}
	return nil
}

// RetrieveType specifies what to retrieve.
type RetrieveType string

const (
	RetrieveRequests             RetrieveType = "requests"
	RetrieveRequestResponses     RetrieveType = "request_responses"
	RetrieveRecordedExpectations RetrieveType = "recorded_expectations"
	RetrieveActiveExpectations   RetrieveType = "active_expectations"
	RetrieveLogs                 RetrieveType = "logs"
)

// RetrieveFormat specifies the response format.
type RetrieveFormat string

const (
	FormatJSON       RetrieveFormat = "json"
	FormatLogEntries RetrieveFormat = "log_entries"
)

// Retrieve retrieves recorded data from MockServer. The result is the raw JSON
// response body. Use the typed helpers (RetrieveActiveExpectations, etc.) for
// convenience.
func (c *Client) Retrieve(rb *RequestBuilder, retrieveType RetrieveType, format RetrieveFormat) ([]byte, error) {
	var body []byte
	var err error
	if rb != nil {
		req := rb.Build()
		body, err = json.Marshal(req)
		if err != nil {
			return nil, fmt.Errorf("mockserver: marshal retrieve filter: %w", err)
		}
	}

	params := make(url.Values)
	if retrieveType != "" {
		params.Set("type", string(retrieveType))
	}
	if format != "" {
		params.Set("format", string(format))
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/retrieve", body, params)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: retrieve failed (status %d): %s", statusCode, string(respBody))
	}
	return respBody, nil
}

// RetrieveActiveExpectations retrieves active expectations, optionally filtered.
func (c *Client) RetrieveActiveExpectations(rb *RequestBuilder) ([]Expectation, error) {
	data, err := c.Retrieve(rb, RetrieveActiveExpectations, FormatJSON)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 {
		return nil, nil
	}
	var result []Expectation
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, fmt.Errorf("mockserver: unmarshal active expectations: %w", err)
	}
	return result, nil
}

// RetrieveRecordedRequests retrieves recorded requests, optionally filtered.
func (c *Client) RetrieveRecordedRequests(rb *RequestBuilder) ([]HttpRequest, error) {
	data, err := c.Retrieve(rb, RetrieveRequests, FormatJSON)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 {
		return nil, nil
	}
	var result []HttpRequest
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, fmt.Errorf("mockserver: unmarshal recorded requests: %w", err)
	}
	return result, nil
}

// RetrieveRecordedExpectations retrieves recorded expectations, optionally filtered.
func (c *Client) RetrieveRecordedExpectations(rb *RequestBuilder) ([]Expectation, error) {
	data, err := c.Retrieve(rb, RetrieveRecordedExpectations, FormatJSON)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 {
		return nil, nil
	}
	var result []Expectation
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, fmt.Errorf("mockserver: unmarshal recorded expectations: %w", err)
	}
	return result, nil
}

// RetrieveLogMessages retrieves log messages, optionally filtered.
func (c *Client) RetrieveLogMessages(rb *RequestBuilder) ([]byte, error) {
	return c.Retrieve(rb, RetrieveLogs, FormatJSON)
}

// StatusResponse contains the response from the status endpoint.
type StatusResponse struct {
	Ports []int `json:"ports"`
}

// Status checks that MockServer is running and returns its listening ports.
func (c *Client) Status() (*StatusResponse, error) {
	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/status", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: status failed (status %d)", statusCode)
	}
	var s StatusResponse
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &s); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal status: %w", err)
		}
	}
	return &s, nil
}

// Bind binds additional ports on the running MockServer instance.
func (c *Client) Bind(ports ...int) ([]int, error) {
	payload := map[string][]int{"ports": ports}
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("mockserver: marshal bind: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/bind", body, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: bind failed (status %d): %s", statusCode, string(respBody))
	}

	var result map[string][]int
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &result); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal bind response: %w", err)
		}
	}
	return result["ports"], nil
}

// IsRunning returns true if MockServer is reachable and responds to a status request.
func (c *Client) IsRunning() bool {
	_, err := c.Status()
	return err == nil
}

// doRequest performs an HTTP request to MockServer.
func (c *Client) doRequest(method, path string, body []byte, params url.Values) ([]byte, int, error) {
	u := c.baseURL + path
	if len(params) > 0 {
		u = u + "?" + params.Encode()
	}

	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := http.NewRequest(method, u, bodyReader)
	if err != nil {
		return nil, 0, fmt.Errorf("mockserver: create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("mockserver: request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, fmt.Errorf("mockserver: read response: %w", err)
	}

	return respBody, resp.StatusCode, nil
}
