package mockserver

import (
	"encoding/json"
	"fmt"
)

// HttpClassCallback is a declarative (REST-only) callback that references a
// server-side class implementing a MockServer callback interface. The class is
// resolved and invoked inside the running MockServer JVM — no WebSocket is
// involved, so this works from any client over plain JSON.
//
// CallbackClass is the fully-qualified name of a class on the MockServer
// classpath (e.g. "com.example.MyExpectationResponseCallback"). Delay, when set,
// is applied before the callback's action is returned. Primary marks the
// callback as the primary action when several actions are present.
type HttpClassCallback struct {
	CallbackClass string `json:"callbackClass"`
	Delay         *Delay `json:"delay,omitempty"`
	Primary       *bool  `json:"primary,omitempty"`
}

// HttpObjectCallback is a closure/object callback driven over the callback
// WebSocket. ClientId is the id the server assigned to this client's callback
// WebSocket connection; on a match the server sends the request over that socket
// and the registered handler produces the response (or forward request).
//
// ResponseCallback, when true, tells MockServer the callback returns a response
// (rather than a forwarded request). It is set automatically by the
// MockWithCallback / MockWithForwardCallback helpers and rarely needs to be set
// by hand.
type HttpObjectCallback struct {
	ClientId         string `json:"clientId"`
	ResponseCallback *bool  `json:"responseCallback,omitempty"`
	Delay            *Delay `json:"delay,omitempty"`
	Primary          *bool  `json:"primary,omitempty"`
}

// --- Class-callback builder methods on ForwardChainExpectation ---

// RespondWithClassCallback completes the expectation with a response class
// callback action: when the request matches, MockServer instantiates the named
// class (which must implement ExpectationResponseCallback and be on the server
// classpath) and uses the response it returns.
func (f *ForwardChainExpectation) RespondWithClassCallback(callbackClass string) ([]Expectation, error) {
	f.expectation.HttpResponseClassCallback = &HttpClassCallback{CallbackClass: callbackClass}
	return f.client.Upsert(f.expectation)
}

// RespondWithClassCallbackAction completes the expectation with a fully
// specified response class callback (allowing a delay or the primary flag to be
// set on the HttpClassCallback).
func (f *ForwardChainExpectation) RespondWithClassCallbackAction(callback *HttpClassCallback) ([]Expectation, error) {
	f.expectation.HttpResponseClassCallback = callback
	return f.client.Upsert(f.expectation)
}

// ForwardWithClassCallback completes the expectation with a forward class
// callback action: when the request matches, MockServer instantiates the named
// class (which must implement ExpectationForwardCallback and be on the server
// classpath) and forwards the request it returns.
func (f *ForwardChainExpectation) ForwardWithClassCallback(callbackClass string) ([]Expectation, error) {
	f.expectation.HttpForwardClassCallback = &HttpClassCallback{CallbackClass: callbackClass}
	return f.client.Upsert(f.expectation)
}

// ForwardWithClassCallbackAction completes the expectation with a fully
// specified forward class callback (allowing a delay or the primary flag to be
// set on the HttpClassCallback).
func (f *ForwardChainExpectation) ForwardWithClassCallbackAction(callback *HttpClassCallback) ([]Expectation, error) {
	f.expectation.HttpForwardClassCallback = callback
	return f.client.Upsert(f.expectation)
}

// --- Object/closure-callback handler types ---

// ObjectResponseCallback receives the matched request and returns the response
// MockServer should send. The returned response is serialized and sent back over
// the callback WebSocket. Return nil to fall back to an empty 200 response.
type ObjectResponseCallback func(request *HttpRequest) *HttpResponse

// ObjectForwardCallback receives the matched request and returns the request
// MockServer should forward upstream. Return nil to forward the original request
// unchanged.
type ObjectForwardCallback func(request *HttpRequest) *HttpRequest

// --- Client object-callback API ---

// MockWithCallback registers an expectation whose response is produced by the
// given Go closure at request time. It mirrors the Node client's mockWithCallback:
//
//  1. it ensures a single shared callback WebSocket for this client (reusing the
//     breakpoint WebSocket transport, so a second socket is never opened),
//  2. it registers handler as the object-callback response handler, and
//  3. it creates an expectation carrying httpResponseObjectCallback.clientId.
//
// On a match the server sends the request over the WebSocket, handler is invoked,
// and its HttpResponse is returned (with the WebSocketCorrelationId echoed so the
// server can route the reply). Pass options (e.g. WithTimes / WithTimeToLive) to
// constrain the expectation.
func (c *Client) MockWithCallback(
	rb *RequestBuilder,
	handler ObjectResponseCallback,
	opts ...ExpectationOption,
) ([]Expectation, error) {
	if rb == nil {
		return nil, fmt.Errorf("mockserver: MockWithCallback requires a non-nil request matcher")
	}
	if handler == nil {
		return nil, fmt.Errorf("mockserver: MockWithCallback requires a non-nil handler")
	}

	if err := c.ensureBreakpointWS(); err != nil {
		return nil, err
	}

	c.breakpointWS.mu.Lock()
	c.breakpointWS.objectResponseHandler = handler
	clientID := c.breakpointWS.clientID
	c.breakpointWS.mu.Unlock()

	req := rb.Build()
	responseCallback := true
	exp := Expectation{
		HttpRequest: &req,
		HttpResponseObjectCallback: &HttpObjectCallback{
			ClientId:         clientID,
			ResponseCallback: &responseCallback,
		},
	}
	for _, opt := range opts {
		opt(&exp)
	}
	return c.Upsert(exp)
}

// MockWithForwardCallback registers an expectation whose forwarded request is
// produced by the given Go closure at request time. It mirrors MockWithCallback
// but for the forward action: handler returns the HttpRequest to forward upstream
// and the reply is sent as an org.mockserver.model.HttpRequest envelope.
func (c *Client) MockWithForwardCallback(
	rb *RequestBuilder,
	handler ObjectForwardCallback,
	opts ...ExpectationOption,
) ([]Expectation, error) {
	if rb == nil {
		return nil, fmt.Errorf("mockserver: MockWithForwardCallback requires a non-nil request matcher")
	}
	if handler == nil {
		return nil, fmt.Errorf("mockserver: MockWithForwardCallback requires a non-nil handler")
	}

	if err := c.ensureBreakpointWS(); err != nil {
		return nil, err
	}

	c.breakpointWS.mu.Lock()
	c.breakpointWS.objectForwardHandler = handler
	clientID := c.breakpointWS.clientID
	c.breakpointWS.mu.Unlock()

	req := rb.Build()
	exp := Expectation{
		HttpRequest: &req,
		HttpForwardObjectCallback: &HttpObjectCallback{
			ClientId: clientID,
		},
	}
	for _, opt := range opts {
		opt(&exp)
	}
	return c.Upsert(exp)
}

// handleObjectResponseCallback handles an incoming object-callback request frame
// (an org.mockserver.model.HttpRequest with no breakpoint id) by invoking the
// registered response handler and replying with an HttpResponse envelope.
func (ws *breakpointWSClient) handleObjectResponseCallback(request map[string]interface{}, correlationID string, handler ObjectResponseCallback) {
	httpReq := decodeHttpRequest(request)

	var resp *HttpResponse
	func() {
		defer func() {
			if r := recover(); r != nil {
				resp = nil
			}
		}()
		resp = handler(httpReq)
	}()

	// Build the reply value as a generic map so the WebSocketCorrelationId header
	// can be merged in alongside whatever the handler set.
	respMap := httpResponseToMap(resp)
	setHeader(respMap, "WebSocketCorrelationId", correlationID)
	ws.sendEnvelope("org.mockserver.model.HttpResponse", respMap)
}

// handleObjectForwardCallback handles an incoming object-callback request frame
// for a forward callback by invoking the registered forward handler and replying
// with an HttpRequest envelope (the request to forward upstream).
func (ws *breakpointWSClient) handleObjectForwardCallback(request map[string]interface{}, correlationID string, handler ObjectForwardCallback) {
	httpReq := decodeHttpRequest(request)

	var fwd *HttpRequest
	func() {
		defer func() {
			if r := recover(); r != nil {
				fwd = nil
			}
		}()
		fwd = handler(httpReq)
	}()

	var reqMap map[string]interface{}
	if fwd != nil {
		reqMap = httpRequestToMap(fwd)
	} else {
		// Forward the original request unchanged.
		reqMap = request
	}
	setHeader(reqMap, "WebSocketCorrelationId", correlationID)
	ws.sendEnvelope("org.mockserver.model.HttpRequest", reqMap)
}

// decodeHttpRequest converts the raw request map received over the WebSocket into
// a typed *HttpRequest. It round-trips through JSON so the same field mapping as
// the rest of the client is used. On any error it returns an empty *HttpRequest
// rather than nil so handlers can always dereference it.
func decodeHttpRequest(request map[string]interface{}) *HttpRequest {
	out := &HttpRequest{}
	raw, err := json.Marshal(request)
	if err != nil {
		return out
	}
	_ = json.Unmarshal(raw, out)
	return out
}

// httpResponseToMap converts an *HttpResponse to a generic map suitable for the
// reply envelope. A nil response becomes an empty map (an empty 200 response).
func httpResponseToMap(resp *HttpResponse) map[string]interface{} {
	if resp == nil {
		return make(map[string]interface{})
	}
	raw, err := json.Marshal(resp)
	if err != nil {
		return make(map[string]interface{})
	}
	out := make(map[string]interface{})
	_ = json.Unmarshal(raw, &out)
	return out
}

// httpRequestToMap converts an *HttpRequest to a generic map suitable for the
// reply envelope.
func httpRequestToMap(req *HttpRequest) map[string]interface{} {
	if req == nil {
		return make(map[string]interface{})
	}
	raw, err := json.Marshal(req)
	if err != nil {
		return make(map[string]interface{})
	}
	out := make(map[string]interface{})
	_ = json.Unmarshal(raw, &out)
	return out
}
