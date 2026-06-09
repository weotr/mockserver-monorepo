package mockserver

// HttpResponse represents an HTTP response action for MockServer.
type HttpResponse struct {
	StatusCode        int                 `json:"statusCode,omitempty"`
	ReasonPhrase      string              `json:"reasonPhrase,omitempty"`
	Headers           map[string][]string `json:"headers,omitempty"`
	Cookies           map[string]string   `json:"cookies,omitempty"`
	Body              interface{}         `json:"body,omitempty"`
	Delay             *Delay              `json:"delay,omitempty"`
	ConnectionOptions *ConnectionOptions  `json:"connectionOptions,omitempty"`
}

// Delay represents a response delay.
type Delay struct {
	TimeUnit string `json:"timeUnit"`
	Value    int    `json:"value"`
}

// ConnectionOptions represents connection-level response options.
type ConnectionOptions struct {
	SuppressContentLengthHeader *bool  `json:"suppressContentLengthHeader,omitempty"`
	ContentLengthHeaderOverride *int   `json:"contentLengthHeaderOverride,omitempty"`
	SuppressConnectionHeader    *bool  `json:"suppressConnectionHeader,omitempty"`
	KeepAliveOverride           *bool  `json:"keepAliveOverride,omitempty"`
	CloseSocket                 *bool  `json:"closeSocket,omitempty"`
	CloseSocketDelay            *Delay `json:"closeSocketDelay,omitempty"`
}

// ResponseBuilder provides a fluent API for building HttpResponse actions.
type ResponseBuilder struct {
	response HttpResponse
}

// Response creates a new ResponseBuilder.
func Response() *ResponseBuilder {
	return &ResponseBuilder{}
}

// StatusCode sets the HTTP status code.
func (b *ResponseBuilder) StatusCode(code int) *ResponseBuilder {
	b.response.StatusCode = code
	return b
}

// ReasonPhrase sets the HTTP reason phrase.
func (b *ResponseBuilder) ReasonPhrase(phrase string) *ResponseBuilder {
	b.response.ReasonPhrase = phrase
	return b
}

// Header adds a response header.
func (b *ResponseBuilder) Header(name string, values ...string) *ResponseBuilder {
	if b.response.Headers == nil {
		b.response.Headers = make(map[string][]string)
	}
	b.response.Headers[name] = values
	return b
}

// Cookie adds a response cookie.
func (b *ResponseBuilder) Cookie(name, value string) *ResponseBuilder {
	if b.response.Cookies == nil {
		b.response.Cookies = make(map[string]string)
	}
	b.response.Cookies[name] = value
	return b
}

// Body sets the response body as a plain string.
func (b *ResponseBuilder) Body(body string) *ResponseBuilder {
	b.response.Body = body
	return b
}

// JSONBody sets the response body as a JSON string with Content-Type header.
func (b *ResponseBuilder) JSONBody(jsonStr string) *ResponseBuilder {
	b.response.Body = jsonStr
	if b.response.Headers == nil {
		b.response.Headers = make(map[string][]string)
	}
	b.response.Headers["Content-Type"] = []string{"application/json"}
	return b
}

// WithDelay sets the response delay.
func (b *ResponseBuilder) WithDelay(timeUnit string, value int) *ResponseBuilder {
	b.response.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// Build returns the constructed HttpResponse.
func (b *ResponseBuilder) Build() HttpResponse {
	return b.response
}
