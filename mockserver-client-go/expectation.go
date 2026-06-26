package mockserver

// Expectation represents a MockServer expectation (request matcher + action).
type Expectation struct {
	ID                   string          `json:"id,omitempty"`
	Priority             int             `json:"priority,omitempty"`
	HttpRequest          *HttpRequest    `json:"httpRequest,omitempty"`
	HttpResponse         *HttpResponse   `json:"httpResponse,omitempty"`
	HttpResponses        []*HttpResponse `json:"httpResponses,omitempty"`
	HttpResponseTemplate *HttpTemplate   `json:"httpResponseTemplate,omitempty"`
	HttpForward          *HttpForward    `json:"httpForward,omitempty"`
	HttpForwardTemplate  *HttpTemplate   `json:"httpForwardTemplate,omitempty"`
	// HttpOverrideForwardedRequest forwards the matched request to an overridden
	// target (RequestOverride) while shaping the caller's response via
	// ResponseOverride or ResponseTemplate.
	HttpOverrideForwardedRequest *HttpOverrideForwardedRequest `json:"httpOverrideForwardedRequest,omitempty"`
	HttpError                    *HttpError                    `json:"httpError,omitempty"`
	// Class callbacks reference a server-side class (REST-only, no WebSocket).
	HttpResponseClassCallback *HttpClassCallback `json:"httpResponseClassCallback,omitempty"`
	HttpForwardClassCallback  *HttpClassCallback `json:"httpForwardClassCallback,omitempty"`
	// Object/closure callbacks are driven over the callback WebSocket; ClientId
	// identifies this client's WebSocket connection.
	HttpResponseObjectCallback *HttpObjectCallback     `json:"httpResponseObjectCallback,omitempty"`
	HttpForwardObjectCallback  *HttpObjectCallback     `json:"httpForwardObjectCallback,omitempty"`
	HttpSseResponse            *HttpSseResponse        `json:"httpSseResponse,omitempty"`
	HttpWebSocketResponse      *HttpWebSocketResponse  `json:"httpWebSocketResponse,omitempty"`
	GrpcStreamResponse         *GrpcStreamResponse     `json:"grpcStreamResponse,omitempty"`
	BinaryResponse             *BinaryResponse         `json:"binaryResponse,omitempty"`
	DnsResponse                *DnsResponse            `json:"dnsResponse,omitempty"`
	HttpLlmResponse            *HttpLlmResponse        `json:"httpLlmResponse,omitempty"`
	ScenarioName               string                  `json:"scenarioName,omitempty"`
	ScenarioState              string                  `json:"scenarioState,omitempty"`
	NewScenarioState           string                  `json:"newScenarioState,omitempty"`
	ResponseMode               ResponseMode            `json:"responseMode,omitempty"`
	ResponseWeights            []int                   `json:"responseWeights,omitempty"`
	SwitchAfter                *int                    `json:"switchAfter,omitempty"`
	CrossProtocolScenarios     []CrossProtocolScenario `json:"crossProtocolScenarios,omitempty"`
	Times                      *Times                  `json:"times,omitempty"`
	TimeToLive                 *TimeToLive             `json:"timeToLive,omitempty"`
}

// ResponseMode selects how MockServer chooses between multiple HttpResponses
// (see Expectation.HttpResponses).
type ResponseMode string

const (
	// ResponseModeSequential cycles through the responses in order (the default).
	ResponseModeSequential ResponseMode = "SEQUENTIAL"
	// ResponseModeRandom picks a response at random.
	ResponseModeRandom ResponseMode = "RANDOM"
	// ResponseModeWeighted picks a response using ResponseWeights as relative weights.
	ResponseModeWeighted ResponseMode = "WEIGHTED"
	// ResponseModeSwitch advances to the next response after SwitchAfter requests.
	ResponseModeSwitch ResponseMode = "SWITCH"
)

// CrossProtocolTrigger identifies the kind of event that advances a scenario
// state machine across protocols.
type CrossProtocolTrigger string

const (
	CrossProtocolTriggerDNSQuery         CrossProtocolTrigger = "DNS_QUERY"
	CrossProtocolTriggerWebSocketConnect CrossProtocolTrigger = "WEBSOCKET_CONNECT"
	CrossProtocolTriggerGrpcRequest      CrossProtocolTrigger = "GRPC_REQUEST"
	CrossProtocolTriggerHTTPRequest      CrossProtocolTrigger = "HTTP_REQUEST"
)

// CrossProtocolScenario advances a named scenario to a target state when an
// event of the given Trigger kind is observed (optionally filtered by
// MatchPattern, a substring of the event identifier).
type CrossProtocolScenario struct {
	Trigger      CrossProtocolTrigger `json:"trigger"`
	MatchPattern string               `json:"matchPattern,omitempty"`
	ScenarioName string               `json:"scenarioName"`
	TargetState  string               `json:"targetState"`
}

// Times controls how many times an expectation can be matched.
type Times struct {
	RemainingTimes int  `json:"remainingTimes,omitempty"`
	Unlimited      bool `json:"unlimited"`
}

// TimeToLive controls how long an expectation remains active.
type TimeToLive struct {
	TimeUnit   string `json:"timeUnit,omitempty"`
	TimeToLive int    `json:"timeToLive,omitempty"`
	Unlimited  bool   `json:"unlimited"`
}

// Once returns a Times that matches exactly once.
func Once() *Times {
	return &Times{RemainingTimes: 1, Unlimited: false}
}

// Exactly returns a Times that matches exactly n times.
func Exactly(n int) *Times {
	return &Times{RemainingTimes: n, Unlimited: false}
}

// Unlimited returns a Times that matches unlimited times.
func Unlimited() *Times {
	return &Times{Unlimited: true}
}

// TTL returns a TimeToLive with the given duration.
func TTL(timeUnit string, value int) *TimeToLive {
	return &TimeToLive{TimeUnit: timeUnit, TimeToLive: value, Unlimited: false}
}

// UnlimitedTTL returns an unlimited TimeToLive.
func UnlimitedTTL() *TimeToLive {
	return &TimeToLive{Unlimited: true}
}
