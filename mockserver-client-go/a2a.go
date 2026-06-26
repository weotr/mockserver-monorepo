package mockserver

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// A2A (Agent2Agent) mock builder for the MockServer Go client.
//
// This is an idiomatic Go port of the Java client A2aMockBuilder
// (org.mockserver.client.A2aMockBuilder). It builds the set of MockServer
// expectations needed to mock an A2A agent: a GET agent-card discovery document
// plus the JSON-RPC 2.0 task methods (tasks/send, tasks/get, tasks/cancel) over
// HTTP, with optional SSE streaming and push-notification delivery. The produced
// expectation JSON is wire-compatible with the Java builder so both drive the
// same server behaviour.
//
// Usage:
//
//	mockserver.A2aMock("/a2a").
//	    WithAgentName("WeatherAgent").
//	    WithSkill("forecast", func(s *mockserver.A2aSkillBuilder) {
//	        s.WithName("Forecast").
//	            WithDescription("Provides weather forecasts").
//	            WithTag("weather").
//	            WithExample("What is the weather in London?")
//	    }).
//	    OnTaskSend("(?i).*london.*", "Sunny in London", false).
//	    ApplyTo(client)

// ---------------------------------------------------------------------------
// Internal definitions
// ---------------------------------------------------------------------------

type a2aSkill struct {
	id          string
	name        string
	hasName     bool
	description string
	hasDesc     bool
	tags        []string
	examples    []string
}

type a2aTaskHandler struct {
	messagePattern string
	responseText   string
	isError        bool
}

// ---------------------------------------------------------------------------
// A2aMockBuilder
// ---------------------------------------------------------------------------

// A2aMockBuilder builds the expectations that emulate an A2A agent.
type A2aMockBuilder struct {
	path                string
	agentCardPath       string
	agentName           string
	agentDescription    string
	agentVersion        string
	agentURL            string
	hasAgentURL         bool
	defaultTaskResponse string
	streaming           bool
	streamingMethod     string
	pushNotificationURL string
	hasPushNotification bool
	skills              []a2aSkill
	taskHandlers        []a2aTaskHandler
}

// A2aMock creates a new A2A mock builder. path defaults to "/a2a" when empty.
func A2aMock(path string) *A2aMockBuilder {
	if path == "" {
		path = "/a2a"
	}
	return &A2aMockBuilder{
		path:                path,
		agentCardPath:       "/.well-known/agent.json",
		agentName:           "MockAgent",
		agentDescription:    "A mock A2A agent",
		agentVersion:        "1.0.0",
		defaultTaskResponse: "Task completed successfully",
		streamingMethod:     "message/stream",
	}
}

// WithAgentName sets the agent name reported by the agent card.
func (b *A2aMockBuilder) WithAgentName(name string) *A2aMockBuilder {
	b.agentName = name
	return b
}

// WithAgentDescription sets the agent description reported by the agent card.
func (b *A2aMockBuilder) WithAgentDescription(description string) *A2aMockBuilder {
	b.agentDescription = description
	return b
}

// WithAgentVersion sets the agent version reported by the agent card.
func (b *A2aMockBuilder) WithAgentVersion(version string) *A2aMockBuilder {
	b.agentVersion = version
	return b
}

// WithAgentURL sets the agent URL reported by the agent card. When unset it
// defaults to "http://localhost" + path.
func (b *A2aMockBuilder) WithAgentURL(agentURL string) *A2aMockBuilder {
	b.agentURL = agentURL
	b.hasAgentURL = true
	return b
}

// WithAgentCardPath overrides the GET path of the agent-card discovery document
// (default "/.well-known/agent.json").
func (b *A2aMockBuilder) WithAgentCardPath(path string) *A2aMockBuilder {
	b.agentCardPath = path
	return b
}

// WithDefaultTaskResponse sets the text returned by tasks/send and tasks/get
// when no custom task handler matches.
func (b *A2aMockBuilder) WithDefaultTaskResponse(response string) *A2aMockBuilder {
	b.defaultTaskResponse = response
	return b
}

// WithStreaming advertises and mocks the A2A streaming capability. When enabled
// the agent card reports capabilities.streaming: true and the streaming JSON-RPC
// method (default "message/stream") returns an SSE stream of status/artifact
// update events wrapped in JSON-RPC 2.0 envelopes.
func (b *A2aMockBuilder) WithStreaming() *A2aMockBuilder {
	b.streaming = true
	return b
}

// WithStreamingMethod overrides the JSON-RPC method that triggers the streaming
// response (e.g. the legacy "tasks/sendSubscribe"). Implies WithStreaming.
func (b *A2aMockBuilder) WithStreamingMethod(method string) *A2aMockBuilder {
	b.streamingMethod = method
	b.streaming = true
	return b
}

// WithPushNotifications advertises and mocks A2A push notifications. When set the
// agent card reports capabilities.pushNotifications: true, the
// tasks/pushNotificationConfig/set method echoes the registered config, and each
// tasks/send additionally POSTs the completed task to the supplied webhook URL
// while still returning the JSON-RPC task response to the caller.
func (b *A2aMockBuilder) WithPushNotifications(webhookURL string) *A2aMockBuilder {
	b.pushNotificationURL = webhookURL
	b.hasPushNotification = true
	return b
}

// A2aSkillBuilder configures a single advertised A2A skill.
type A2aSkillBuilder struct {
	skill *a2aSkill
}

// WithName sets the skill name (defaults to the skill id when unset).
func (s *A2aSkillBuilder) WithName(name string) *A2aSkillBuilder {
	s.skill.name = name
	s.skill.hasName = true
	return s
}

// WithDescription sets the skill description.
func (s *A2aSkillBuilder) WithDescription(description string) *A2aSkillBuilder {
	s.skill.description = description
	s.skill.hasDesc = true
	return s
}

// WithTag appends a skill tag.
func (s *A2aSkillBuilder) WithTag(tag string) *A2aSkillBuilder {
	s.skill.tags = append(s.skill.tags, tag)
	return s
}

// WithExample appends a skill example prompt.
func (s *A2aSkillBuilder) WithExample(example string) *A2aSkillBuilder {
	s.skill.examples = append(s.skill.examples, example)
	return s
}

// WithSkill registers an advertised skill, configured via the supplied closure.
func (b *A2aMockBuilder) WithSkill(id string, configure func(*A2aSkillBuilder)) *A2aMockBuilder {
	sb := &A2aSkillBuilder{skill: &a2aSkill{id: id}}
	if configure != nil {
		configure(sb)
	}
	b.skills = append(b.skills, *sb.skill)
	return b
}

// OnTaskSend registers a custom tasks/send handler: when the first text part of
// the message matches messagePattern (a regular expression), the agent returns
// responseText with the given error flag. Handlers are matched (via JSONPath)
// before the default tasks/send response.
func (b *A2aMockBuilder) OnTaskSend(messagePattern, responseText string, isError bool) *A2aMockBuilder {
	if messagePattern == "" {
		messagePattern = ".*"
	}
	b.taskHandlers = append(b.taskHandlers, a2aTaskHandler{
		messagePattern: messagePattern,
		responseText:   responseText,
		isError:        isError,
	})
	return b
}

// ---------------------------------------------------------------------------
// Expectation assembly
// ---------------------------------------------------------------------------

func (b *A2aMockBuilder) buildAgentCard() Expectation {
	var skillsItems []string
	for _, skill := range b.skills {
		var sb strings.Builder
		sb.WriteString("{")
		sb.WriteString(`"id": "` + escapeJson(skill.id) + `"`)
		name := skill.id
		if skill.hasName {
			name = skill.name
		}
		sb.WriteString(`, "name": "` + escapeJson(name) + `"`)
		if skill.hasDesc {
			sb.WriteString(`, "description": "` + escapeJson(skill.description) + `"`)
		}
		if len(skill.tags) > 0 {
			tagItems := make([]string, 0, len(skill.tags))
			for _, tag := range skill.tags {
				tagItems = append(tagItems, `"`+escapeJson(tag)+`"`)
			}
			sb.WriteString(`, "tags": [` + strings.Join(tagItems, ", ") + "]")
		}
		if len(skill.examples) > 0 {
			exItems := make([]string, 0, len(skill.examples))
			for _, ex := range skill.examples {
				exItems = append(exItems, `"`+escapeJson(ex)+`"`)
			}
			sb.WriteString(`, "examples": [` + strings.Join(exItems, ", ") + "]")
		}
		sb.WriteString("}")
		skillsItems = append(skillsItems, sb.String())
	}
	skillsJson := "[" + strings.Join(skillsItems, ", ") + "]"

	agentURL := "http://localhost" + b.path
	if b.hasAgentURL {
		agentURL = b.agentURL
	}

	agentCardJson := "{" +
		`"name": "` + escapeJson(b.agentName) + `", ` +
		`"description": "` + escapeJson(b.agentDescription) + `", ` +
		`"version": "` + escapeJson(b.agentVersion) + `", ` +
		`"url": "` + escapeJson(agentURL) + `", ` +
		`"capabilities": {"streaming": ` + boolStr(b.streaming) +
		`, "pushNotifications": ` + boolStr(b.hasPushNotification) +
		`, "stateTransitionHistory": false}, ` +
		`"skills": ` + skillsJson + "}"

	return Expectation{
		HttpRequest: &HttpRequest{Method: "GET", Path: b.agentCardPath},
		HttpResponse: &HttpResponse{
			StatusCode: 200,
			Headers:    map[string][]string{"Content-Type": {"application/json"}},
			Body:       agentCardJson,
		},
	}
}

func (b *A2aMockBuilder) buildTasksSend() Expectation {
	resultJson := b.taskResultJson(b.defaultTaskResponse, false, true)
	return velocityTemplateExpectation(jsonRpcRequest(b.path, "tasks/send"), resultJson)
}

func (b *A2aMockBuilder) buildTasksGet() Expectation {
	resultJson := b.taskResultJson(b.defaultTaskResponse, false, true)
	return velocityTemplateExpectation(jsonRpcRequest(b.path, "tasks/get"), resultJson)
}

func (b *A2aMockBuilder) buildTasksCancel() Expectation {
	resultJson := `{"id": "mock-task-id", "status": {"state": "canceled"}}`
	return velocityTemplateExpectation(jsonRpcRequest(b.path, "tasks/cancel"), resultJson)
}

func (b *A2aMockBuilder) buildStreaming() Expectation {
	text := escapeJson(b.defaultTaskResponse)
	taskID := "mock-task-id"

	closeConnection := true
	sse := &HttpSseResponse{
		StatusCode: 200,
		Events: []SseEvent{
			{
				Event: "message",
				Data: `{"jsonrpc": "2.0", "id": "1", "result": ` +
					`{"taskId": "` + taskID + `", "kind": "status-update", ` +
					`"status": {"state": "working"}, "final": false}}`,
			},
			{
				Event: "message",
				Data: `{"jsonrpc": "2.0", "id": "1", "result": ` +
					`{"taskId": "` + taskID + `", "kind": "artifact-update", ` +
					`"artifact": {"parts": [{"type": "text", "text": "` + text + `"}]}}}`,
			},
			{
				Event: "message",
				Data: `{"jsonrpc": "2.0", "id": "1", "result": ` +
					`{"taskId": "` + taskID + `", "kind": "status-update", ` +
					`"status": {"state": "completed"}, "final": true}}`,
			},
		},
		CloseConnection: &closeConnection,
	}

	return Expectation{
		HttpRequest:     jsonRpcRequest(b.path, b.streamingMethod),
		HttpSseResponse: sse,
	}
}

func (b *A2aMockBuilder) buildPushNotificationConfig() Expectation {
	// Echo the registered push-notification config back as the JSON-RPC result.
	resultJson := `{"url": "` + escapeVelocity(escapeJson(b.pushNotificationURL)) + `"}`
	return velocityTemplateExpectation(jsonRpcRequest(b.path, "tasks/pushNotificationConfig/set"), resultJson)
}

func (b *A2aMockBuilder) buildPushNotificationDelivery() Expectation {
	// A tasks/send both returns the JSON-RPC task response to the caller AND POSTs
	// the completed task to the configured webhook. Modelled as an
	// override-forwarded-request: the request override targets the webhook (literal
	// body, JSON-escaped only), and a Velocity response template produces the
	// caller's JSON-RPC response so the request's id is echoed back.
	target := parseWebhookTarget(b.pushNotificationURL)

	pushBody := `{"jsonrpc": "2.0", "result": ` + b.taskResultJson(b.defaultTaskResponse, false, false) + "}"

	secure := target.secure
	scheme := "HTTP"
	if secure {
		scheme = "HTTPS"
	}
	webhookRequest := &HttpRequest{
		Method:        "POST",
		Path:          target.path,
		SocketAddress: &SocketAddress{Host: target.host, Port: target.port, Scheme: scheme},
		Secure:        &secure,
		Headers: map[string][]string{
			"Host":         {target.hostHeader()},
			"Content-Type": {"application/json"},
		},
		Body: pushBody,
	}

	clientResponseTemplate := &HttpTemplate{
		TemplateType: "VELOCITY",
		Template:     velocityJsonRpcResponse(b.taskResultJson(b.defaultTaskResponse, false, true)),
	}

	return Expectation{
		HttpRequest: jsonRpcRequest(b.path, "tasks/send"),
		HttpOverrideForwardedRequest: &HttpOverrideForwardedRequest{
			RequestOverride:  webhookRequest,
			ResponseTemplate: clientResponseTemplate,
		},
	}
}

func (b *A2aMockBuilder) buildCustomTaskHandler(handler a2aTaskHandler) Expectation {
	escapedPattern := escapeMessagePattern(handler.messagePattern)
	jsonPath := "$[?(@.method == 'tasks/send' && @.params.message.parts[0].text =~ /" + escapedPattern + "/)]"
	resultJson := b.taskResultJson(handler.responseText, handler.isError, true)
	return velocityTemplateExpectation(jsonPathRequest(b.path, jsonPath), resultJson)
}

// escapeMessagePattern neutralises the characters that could let a
// user-supplied messagePattern (a regular expression) break out of the
// /.../ regex literal embedded in the JSONPath matcher, while preserving the
// pattern's own regex escape sequences (e.g. \d, \/, \\).
//
// The scan is single-pass and left-to-right over BYTES — every special
// character handled here is ASCII, and any other byte (including the
// continuation bytes of multi-byte UTF-8 runes) is emitted verbatim, so valid
// UTF-8 input is preserved unchanged.
//
//   - On a backslash: if another byte follows, the backslash and that byte are
//     both emitted verbatim so an existing escape sequence (\d, \/, \\, ...) is
//     left intact; a TRAILING backslash with nothing to escape is doubled to
//     \\ so it cannot escape the closing "/" delimiter and break out.
//   - A bare "/" is escaped to \/ so it cannot close the regex literal early.
//   - Newline / carriage-return are converted to \n / \r.
//   - A NUL byte is stripped.
//   - Any other byte is emitted verbatim.
//
// For every input that contains none of these breakout cases the output is
// byte-identical to the previous inline escaping.
func escapeMessagePattern(pattern string) string {
	var b strings.Builder
	for i := 0; i < len(pattern); i++ {
		c := pattern[i]
		switch {
		case c == '\\':
			if i+1 < len(pattern) {
				b.WriteByte('\\')
				b.WriteByte(pattern[i+1])
				i++
			} else {
				b.WriteString(`\\`)
			}
		case c == '/':
			b.WriteString(`\/`)
		case c == '\n':
			b.WriteString(`\n`)
		case c == '\r':
			b.WriteString(`\r`)
		case c == 0x00:
			// strip NUL
		default:
			b.WriteByte(c)
		}
	}
	return b.String()
}

// taskResultJson builds the A2A task result object. When velocity is true the
// text is escaped for survival through the Velocity engine (templated response
// bodies); when false only JSON escaping is applied (literal webhook bodies).
func (b *A2aMockBuilder) taskResultJson(responseText string, isError, velocity bool) string {
	escaped := escapeJson(responseText)
	if velocity {
		escaped = escapeVelocity(escaped)
	}
	state := "completed"
	if isError {
		state = "failed"
	}
	return `{"id": "mock-task-id", ` +
		`"status": {"state": "` + state + `"}, ` +
		`"artifacts": [{"parts": [{"type": "text", "text": "` + escaped + `"}]}]}`
}

// Build returns the ordered list of expectations that emulate the A2A agent.
func (b *A2aMockBuilder) Build() []Expectation {
	expectations := []Expectation{b.buildAgentCard()}

	for _, handler := range b.taskHandlers {
		expectations = append(expectations, b.buildCustomTaskHandler(handler))
	}

	if b.streaming {
		expectations = append(expectations, b.buildStreaming())
	}

	if b.hasPushNotification {
		expectations = append(expectations,
			b.buildPushNotificationConfig(),
			b.buildPushNotificationDelivery())
	} else {
		expectations = append(expectations, b.buildTasksSend())
	}
	expectations = append(expectations, b.buildTasksGet(), b.buildTasksCancel())

	return expectations
}

// ApplyTo registers the A2A expectations with the client via Upsert.
func (b *A2aMockBuilder) ApplyTo(c *Client) ([]Expectation, error) {
	return c.Upsert(b.Build()...)
}

// ---------------------------------------------------------------------------
// Webhook target parsing (mirrors the Java WebhookTarget)
// ---------------------------------------------------------------------------

type webhookTarget struct {
	host   string
	port   int
	secure bool
	path   string
}

func (t webhookTarget) hostHeader() string {
	return t.host + ":" + strconv.Itoa(t.port)
}

// parseWebhookTarget parses a push-notification webhook URL into its components.
// It panics on an invalid URL (no host), mirroring the throwing Java builder.
func parseWebhookTarget(raw string) webhookTarget {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		panic(fmt.Sprintf("mockserver: invalid push-notification webhook URL: %q", raw))
	}
	secure := strings.EqualFold(u.Scheme, "https")
	host := u.Hostname()
	if host == "" {
		panic(fmt.Sprintf("mockserver: invalid push-notification webhook URL (no host): %q", raw))
	}
	port := 80
	if secure {
		port = 443
	}
	if p := u.Port(); p != "" {
		if parsed, perr := strconv.Atoi(p); perr == nil {
			port = parsed
		}
	}
	path := u.EscapedPath()
	if path == "" {
		path = "/"
	}
	return webhookTarget{host: host, port: port, secure: secure, path: path}
}

func boolStr(v bool) string {
	if v {
		return "true"
	}
	return "false"
}
