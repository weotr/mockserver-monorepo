package mockserver

// HttpRequest represents an HTTP request matcher for MockServer.
type HttpRequest struct {
	Method               string              `json:"method,omitempty"`
	Path                 string              `json:"path,omitempty"`
	QueryStringParams    map[string][]string `json:"queryStringParameters,omitempty"`
	Headers              map[string][]string `json:"headers,omitempty"`
	Cookies              map[string]string   `json:"cookies,omitempty"`
	Body                 interface{}         `json:"body,omitempty"`
	Secure               *bool               `json:"secure,omitempty"`
	KeepAlive            *bool               `json:"keepAlive,omitempty"`
	SocketAddress        *SocketAddress      `json:"socketAddress,omitempty"`
	PathParametersList   map[string][]string `json:"pathParameters,omitempty"`
}

// SocketAddress represents a socket address constraint.
type SocketAddress struct {
	Host   string `json:"host,omitempty"`
	Port   int    `json:"port,omitempty"`
	Scheme string `json:"scheme,omitempty"`
}

// RequestBuilder provides a fluent API for building HttpRequest matchers.
type RequestBuilder struct {
	request HttpRequest
}

// Request creates a new RequestBuilder.
func Request() *RequestBuilder {
	return &RequestBuilder{}
}

// Method sets the HTTP method matcher.
func (b *RequestBuilder) Method(method string) *RequestBuilder {
	b.request.Method = method
	return b
}

// Path sets the path matcher.
func (b *RequestBuilder) Path(path string) *RequestBuilder {
	b.request.Path = path
	return b
}

// QueryStringParameter adds a query string parameter matcher.
func (b *RequestBuilder) QueryStringParameter(name string, values ...string) *RequestBuilder {
	if b.request.QueryStringParams == nil {
		b.request.QueryStringParams = make(map[string][]string)
	}
	b.request.QueryStringParams[name] = values
	return b
}

// Header adds a header matcher.
func (b *RequestBuilder) Header(name string, values ...string) *RequestBuilder {
	if b.request.Headers == nil {
		b.request.Headers = make(map[string][]string)
	}
	b.request.Headers[name] = values
	return b
}

// Cookie adds a cookie matcher.
func (b *RequestBuilder) Cookie(name, value string) *RequestBuilder {
	if b.request.Cookies == nil {
		b.request.Cookies = make(map[string]string)
	}
	b.request.Cookies[name] = value
	return b
}

// Body sets the request body matcher as a plain string.
func (b *RequestBuilder) Body(body string) *RequestBuilder {
	b.request.Body = body
	return b
}

// JSONBody sets the request body matcher as a JSON body type.
func (b *RequestBuilder) JSONBody(json string) *RequestBuilder {
	b.request.Body = &TypedBody{Type: "JSON", JSON: json}
	return b
}

// PathParameter adds a path parameter matcher.
func (b *RequestBuilder) PathParameter(name string, values ...string) *RequestBuilder {
	if b.request.PathParametersList == nil {
		b.request.PathParametersList = make(map[string][]string)
	}
	b.request.PathParametersList[name] = values
	return b
}

// Secure sets whether the request must be secure (HTTPS).
func (b *RequestBuilder) Secure(secure bool) *RequestBuilder {
	b.request.Secure = &secure
	return b
}

// KeepAlive sets whether the request must be keep-alive.
func (b *RequestBuilder) KeepAlive(keepAlive bool) *RequestBuilder {
	b.request.KeepAlive = &keepAlive
	return b
}

// Build returns the constructed HttpRequest.
func (b *RequestBuilder) Build() HttpRequest {
	return b.request
}

// TypedBody represents a typed body matcher (e.g., JSON, XML).
type TypedBody struct {
	Type string `json:"type"`
	JSON string `json:"json,omitempty"`
	XML  string `json:"xml,omitempty"`
}
