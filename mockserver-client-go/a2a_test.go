package mockserver

import (
	"encoding/json"
	"strings"
	"testing"
)

// findByJsonRpcMethod returns the first expectation whose request body is a
// JSON_RPC matcher for the given method.
func findByJsonRpcMethod(exps []Expectation, method string) *Expectation {
	for i := range exps {
		if exps[i].HttpRequest == nil {
			continue
		}
		if rpc, ok := exps[i].HttpRequest.Body.(jsonRpcBody); ok && rpc.Method == method {
			return &exps[i]
		}
	}
	return nil
}

func findAgentCard(exps []Expectation, cardPath string) *Expectation {
	for i := range exps {
		if r := exps[i].HttpRequest; r != nil && r.Method == "GET" && r.Path == cardPath {
			return &exps[i]
		}
	}
	return nil
}

func TestA2aMock_DefaultsAndStructure(t *testing.T) {
	exps := A2aMock("").Build() // empty path -> default "/a2a"

	// agent-card, tasks/send, tasks/get, tasks/cancel = 4.
	if len(exps) != 4 {
		t.Fatalf("expected 4 expectations by default, got %d", len(exps))
	}

	// Agent card: GET /.well-known/agent.json -> 200 JSON.
	card := findAgentCard(exps, "/.well-known/agent.json")
	if card == nil {
		t.Fatalf("agent card expectation not found")
	}
	if card.HttpResponse == nil || card.HttpResponse.StatusCode != 200 {
		t.Fatalf("agent card response = %#v, want 200", card.HttpResponse)
	}
	body, _ := card.HttpResponse.Body.(string)
	if !strings.Contains(body, `"name": "MockAgent"`) {
		t.Errorf("agent card missing default name: %q", body)
	}
	if !strings.Contains(body, `"description": "A mock A2A agent"`) {
		t.Errorf("agent card missing default description: %q", body)
	}
	if !strings.Contains(body, `"version": "1.0.0"`) {
		t.Errorf("agent card missing default version: %q", body)
	}
	if !strings.Contains(body, `"url": "http://localhost/a2a"`) {
		t.Errorf("agent card missing default url: %q", body)
	}
	if !strings.Contains(body, `"capabilities": {"streaming": false, "pushNotifications": false, "stateTransitionHistory": false}`) {
		t.Errorf("agent card capabilities wrong: %q", body)
	}
	if !strings.Contains(body, `"skills": []`) {
		t.Errorf("agent card should have empty skills: %q", body)
	}
	// Agent card is a plain JSON body, must parse as valid JSON.
	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(body), &parsed); err != nil {
		t.Fatalf("agent card body is not valid JSON: %v\n%s", err, body)
	}

	// tasks/send + tasks/get are Velocity templates echoing the JSON-RPC id.
	send := findByJsonRpcMethod(exps, "tasks/send")
	if send == nil || send.HttpResponseTemplate == nil {
		t.Fatalf("tasks/send velocity template not found")
	}
	if send.HttpResponseTemplate.TemplateType != "VELOCITY" {
		t.Errorf("tasks/send template type = %q, want VELOCITY", send.HttpResponseTemplate.TemplateType)
	}
	tmpl := send.HttpResponseTemplate.Template
	if !strings.Contains(tmpl, `"id": $!{request.jsonRpcRawId}`) {
		t.Errorf("tasks/send template missing jsonRpcRawId echo: %q", tmpl)
	}
	if !strings.Contains(tmpl, `"state": "completed"`) {
		t.Errorf("tasks/send template missing completed state: %q", tmpl)
	}
	if !strings.Contains(tmpl, `"text": "Task completed successfully"`) {
		t.Errorf("tasks/send template missing default response text: %q", tmpl)
	}

	if findByJsonRpcMethod(exps, "tasks/get") == nil {
		t.Errorf("tasks/get expectation not found")
	}

	// tasks/cancel returns canceled state.
	cancel := findByJsonRpcMethod(exps, "tasks/cancel")
	if cancel == nil {
		t.Fatalf("tasks/cancel expectation not found")
	}
	if !strings.Contains(cancel.HttpResponseTemplate.Template, `"state": "canceled"`) {
		t.Errorf("tasks/cancel missing canceled state: %q", cancel.HttpResponseTemplate.Template)
	}
}

func TestA2aMock_SkillsAndAgentMetadata(t *testing.T) {
	exps := A2aMock("/a2a").
		WithAgentName("WeatherAgent").
		WithAgentDescription("Provides weather").
		WithAgentVersion("2.1.0").
		WithAgentURL("https://agent.example.com/a2a").
		WithDefaultTaskResponse("done").
		WithSkill("forecast", func(s *A2aSkillBuilder) {
			s.WithName("Forecast").
				WithDescription("Weather forecasts").
				WithTag("weather").
				WithTag("climate").
				WithExample("Weather in London?")
		}).
		WithSkill("bare", nil).
		Build()

	card := findAgentCard(exps, "/.well-known/agent.json")
	if card == nil {
		t.Fatalf("agent card not found")
	}
	body := card.HttpResponse.Body.(string)

	if !strings.Contains(body, `"name": "WeatherAgent"`) ||
		!strings.Contains(body, `"description": "Provides weather"`) ||
		!strings.Contains(body, `"version": "2.1.0"`) ||
		!strings.Contains(body, `"url": "https://agent.example.com/a2a"`) {
		t.Errorf("agent metadata wrong: %q", body)
	}
	// Configured skill with all fields.
	if !strings.Contains(body, `{"id": "forecast", "name": "Forecast", "description": "Weather forecasts", "tags": ["weather", "climate"], "examples": ["Weather in London?"]}`) {
		t.Errorf("forecast skill shape wrong: %q", body)
	}
	// Bare skill (no name configured) defaults name to the id.
	if !strings.Contains(body, `{"id": "bare", "name": "bare"}`) {
		t.Errorf("bare skill should default name to id: %q", body)
	}

	// Agent card must remain valid JSON.
	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(body), &parsed); err != nil {
		t.Fatalf("agent card not valid JSON: %v\n%s", err, body)
	}

	// Default task response flows into tasks/send.
	send := findByJsonRpcMethod(exps, "tasks/send")
	if !strings.Contains(send.HttpResponseTemplate.Template, `"text": "done"`) {
		t.Errorf("tasks/send default response wrong: %q", send.HttpResponseTemplate.Template)
	}
}

func TestA2aMock_CustomTaskHandlers(t *testing.T) {
	exps := A2aMock("/a2a").
		OnTaskSend("(?i).*london.*", "Sunny in London", false).
		OnTaskSend("(?i).*error.*", "boom", true).
		Build()

	// agent-card, 2 custom handlers, tasks/send, tasks/get, tasks/cancel = 6.
	if len(exps) != 6 {
		t.Fatalf("expected 6 expectations, got %d", len(exps))
	}

	// Custom handlers are JSON_PATH matchers placed before the default tasks/send.
	var handlers []*Expectation
	for i := range exps {
		if jp, ok := exps[i].HttpRequest.Body.(jsonPathBody); ok && strings.Contains(jp.JSONPath, "tasks/send") {
			handlers = append(handlers, &exps[i])
		}
	}
	if len(handlers) != 2 {
		t.Fatalf("expected 2 JSON_PATH custom handlers, got %d", len(handlers))
	}

	jp := handlers[0].HttpRequest.Body.(jsonPathBody)
	if jp.Type != "JSON_PATH" {
		t.Errorf("custom handler body type = %q, want JSON_PATH", jp.Type)
	}
	wantPath := "$[?(@.method == 'tasks/send' && @.params.message.parts[0].text =~ /(?i).*london.*/)]"
	if jp.JSONPath != wantPath {
		t.Errorf("custom handler jsonPath = %q, want %q", jp.JSONPath, wantPath)
	}
	if !strings.Contains(handlers[0].HttpResponseTemplate.Template, `"text": "Sunny in London"`) {
		t.Errorf("custom handler response wrong: %q", handlers[0].HttpResponseTemplate.Template)
	}
	if !strings.Contains(handlers[0].HttpResponseTemplate.Template, `"state": "completed"`) {
		t.Errorf("non-error handler should be completed: %q", handlers[0].HttpResponseTemplate.Template)
	}

	// Error handler -> failed state.
	if !strings.Contains(handlers[1].HttpResponseTemplate.Template, `"state": "failed"`) {
		t.Errorf("error handler should be failed: %q", handlers[1].HttpResponseTemplate.Template)
	}
}

// jsonPathForPattern builds the custom-handler JSONPath produced for a given
// user-supplied messagePattern by driving the real builder, so the assertions
// below exercise the exact escaping used in production.
func jsonPathForPattern(t *testing.T, pattern string) string {
	t.Helper()
	exps := A2aMock("/a2a").OnTaskSend(pattern, "ok", false).Build()
	for i := range exps {
		if jp, ok := exps[i].HttpRequest.Body.(jsonPathBody); ok && strings.Contains(jp.JSONPath, "tasks/send") {
			return jp.JSONPath
		}
	}
	t.Fatalf("no custom JSON_PATH handler produced for pattern %q", pattern)
	return ""
}

// TestEscapeMessagePattern_PreservesRegexEscapes proves the breakout fix does
// not corrupt ordinary regex patterns: existing escape sequences survive
// unchanged and are NOT doubled.
func TestEscapeMessagePattern_PreservesRegexEscapes(t *testing.T) {
	cases := []struct {
		name    string
		pattern string
		want    string
	}{
		// (1) \d+ stays \d+ (single backslash, NOT doubled).
		{"backslash-d", `\d+`, `\d+`},
		// (2) a\/b stays a\/b (already-escaped slash NOT re-escaped to a\\/b).
		{"escaped-slash", `a\/b`, `a\/b`},
		// (4) a normal slash-containing pattern still escapes exactly as before.
		{"bare-slash", `a/b`, `a\/b`},
		// Plain pattern is byte-identical (regression of the existing happy path).
		{"plain", `(?i).*london.*`, `(?i).*london.*`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := escapeMessagePattern(tc.pattern); got != tc.want {
				t.Errorf("escapeMessagePattern(%q) = %q, want %q", tc.pattern, got, tc.want)
			}
			// The escaped pattern must sit inside the regex literal intact.
			want := "$[?(@.method == 'tasks/send' && @.params.message.parts[0].text =~ /" + tc.want + "/)]"
			if got := jsonPathForPattern(t, tc.pattern); got != want {
				t.Errorf("jsonPath for %q = %q, want %q", tc.pattern, got, want)
			}
		})
	}
}

// TestEscapeMessagePattern_TrailingBackslashNoBreakout is the security
// assertion: a pattern ending in a single backslash must be doubled so it
// cannot escape the closing "/" delimiter, and the produced JSONPath must still
// end with the UNESCAPED closing "/)]".
func TestEscapeMessagePattern_TrailingBackslashNoBreakout(t *testing.T) {
	// (3) Pattern ending in a single backslash -> two backslashes.
	if got, want := escapeMessagePattern(`foo\`), `foo\\`; got != want {
		t.Errorf("escapeMessagePattern(`foo\\`) = %q, want %q", got, want)
	}

	jsonPath := jsonPathForPattern(t, `foo\`)
	want := `$[?(@.method == 'tasks/send' && @.params.message.parts[0].text =~ /foo\\/)]`
	if jsonPath != want {
		t.Errorf("jsonPath = %q, want %q", jsonPath, want)
	}
	// The closing delimiter sequence "/)]" must remain present and unescaped:
	// a breakout would have consumed the leading "/" of "/)]".
	if !strings.HasSuffix(jsonPath, `\\/)]`) {
		t.Errorf("trailing backslash broke out of regex literal: %q", jsonPath)
	}
}

func TestA2aMock_Streaming(t *testing.T) {
	exps := A2aMock("/a2a").
		WithStreaming().
		WithDefaultTaskResponse("streamed text").
		Build()

	// agent-card advertises streaming capability.
	card := findAgentCard(exps, "/.well-known/agent.json")
	if !strings.Contains(card.HttpResponse.Body.(string), `"streaming": true`) {
		t.Errorf("agent card should advertise streaming: %q", card.HttpResponse.Body)
	}

	// Streaming method default is message/stream and uses an SSE response.
	stream := findByJsonRpcMethod(exps, "message/stream")
	if stream == nil {
		t.Fatalf("message/stream expectation not found")
	}
	if stream.HttpSseResponse == nil {
		t.Fatalf("streaming expectation missing SSE response")
	}
	sse := stream.HttpSseResponse
	if len(sse.Events) != 3 {
		t.Fatalf("expected 3 SSE events, got %d", len(sse.Events))
	}
	if sse.CloseConnection == nil || !*sse.CloseConnection {
		t.Errorf("streaming SSE should close connection")
	}
	// Events: working status, artifact update (with text), completed status.
	if !strings.Contains(sse.Events[0].Data, `"state": "working"`) {
		t.Errorf("first SSE event should be working: %q", sse.Events[0].Data)
	}
	if !strings.Contains(sse.Events[1].Data, `"text": "streamed text"`) {
		t.Errorf("artifact SSE event missing text: %q", sse.Events[1].Data)
	}
	if !strings.Contains(sse.Events[2].Data, `"final": true`) {
		t.Errorf("final SSE event should be final: %q", sse.Events[2].Data)
	}
	// Each SSE data payload is a valid JSON-RPC envelope.
	for i, ev := range sse.Events {
		var env map[string]interface{}
		if err := json.Unmarshal([]byte(ev.Data), &env); err != nil {
			t.Fatalf("SSE event %d data not valid JSON: %v\n%s", i, err, ev.Data)
		}
		if env["jsonrpc"] != "2.0" {
			t.Errorf("SSE event %d jsonrpc = %v, want 2.0", i, env["jsonrpc"])
		}
	}
}

func TestA2aMock_StreamingMethodOverrideImpliesStreaming(t *testing.T) {
	exps := A2aMock("/a2a").
		WithStreamingMethod("tasks/sendSubscribe").
		Build()

	card := findAgentCard(exps, "/.well-known/agent.json")
	if !strings.Contains(card.HttpResponse.Body.(string), `"streaming": true`) {
		t.Errorf("streaming method override should imply streaming capability")
	}
	if findByJsonRpcMethod(exps, "tasks/sendSubscribe") == nil {
		t.Errorf("legacy streaming method tasks/sendSubscribe not wired")
	}
	if findByJsonRpcMethod(exps, "message/stream") != nil {
		t.Errorf("default streaming method should not be present after override")
	}
}

func TestA2aMock_PushNotifications(t *testing.T) {
	exps := A2aMock("/a2a").
		WithPushNotifications("http://localhost:1234/a2a/callback").
		WithDefaultTaskResponse("pushed result").
		Build()

	// agent-card, pushConfig set, push delivery (replaces tasks/send),
	// tasks/get, tasks/cancel = 5. No plain tasks/send template.
	if len(exps) != 5 {
		t.Fatalf("expected 5 expectations with push notifications, got %d", len(exps))
	}

	// Agent card advertises pushNotifications.
	card := findAgentCard(exps, "/.well-known/agent.json")
	if !strings.Contains(card.HttpResponse.Body.(string), `"pushNotifications": true`) {
		t.Errorf("agent card should advertise pushNotifications: %q", card.HttpResponse.Body)
	}

	// pushNotificationConfig/set echoes the webhook url.
	cfg := findByJsonRpcMethod(exps, "tasks/pushNotificationConfig/set")
	if cfg == nil {
		t.Fatalf("tasks/pushNotificationConfig/set not found")
	}
	if !strings.Contains(cfg.HttpResponseTemplate.Template, `"url": "http://localhost:1234/a2a/callback"`) {
		t.Errorf("pushNotificationConfig should echo url: %q", cfg.HttpResponseTemplate.Template)
	}

	// tasks/send is modelled as an override-forwarded-request to the webhook.
	send := findByJsonRpcMethod(exps, "tasks/send")
	if send == nil {
		t.Fatalf("tasks/send (push delivery) not found")
	}
	if send.HttpResponseTemplate != nil {
		t.Errorf("push tasks/send should NOT be a plain response template")
	}
	ofr := send.HttpOverrideForwardedRequest
	if ofr == nil {
		t.Fatalf("push tasks/send missing httpOverrideForwardedRequest")
	}
	if ofr.RequestOverride == nil {
		t.Fatalf("push delivery missing request override")
	}
	req := ofr.RequestOverride
	if req.Method != "POST" || req.Path != "/a2a/callback" {
		t.Errorf("webhook request target wrong: method=%q path=%q", req.Method, req.Path)
	}
	if req.SocketAddress == nil || req.SocketAddress.Host != "localhost" ||
		req.SocketAddress.Port != 1234 || req.SocketAddress.Scheme != "HTTP" {
		t.Errorf("webhook socket address wrong: %#v", req.SocketAddress)
	}
	if req.Secure == nil || *req.Secure {
		t.Errorf("webhook should not be secure for http URL")
	}
	if got := req.Headers["Host"]; len(got) != 1 || got[0] != "localhost:1234" {
		t.Errorf("webhook Host header wrong: %#v", req.Headers["Host"])
	}
	// Webhook body is literal (JSON-escaped only) and valid JSON-RPC.
	pushBody, _ := req.Body.(string)
	if !strings.Contains(pushBody, `"text": "pushed result"`) {
		t.Errorf("webhook body missing result text: %q", pushBody)
	}
	var pushEnv map[string]interface{}
	if err := json.Unmarshal([]byte(pushBody), &pushEnv); err != nil {
		t.Fatalf("webhook push body not valid JSON: %v\n%s", err, pushBody)
	}
	// Caller's response template echoes the JSON-RPC id.
	if ofr.ResponseTemplate == nil || ofr.ResponseTemplate.TemplateType != "VELOCITY" {
		t.Fatalf("push delivery missing velocity response template")
	}
	if !strings.Contains(ofr.ResponseTemplate.Template, `"id": $!{request.jsonRpcRawId}`) {
		t.Errorf("push response template should echo jsonRpcRawId: %q", ofr.ResponseTemplate.Template)
	}
}

func TestA2aMock_HttpsPushNotificationDefaultsPort(t *testing.T) {
	exps := A2aMock("/a2a").
		WithPushNotifications("https://hooks.example.com/cb").
		Build()
	send := findByJsonRpcMethod(exps, "tasks/send")
	req := send.HttpOverrideForwardedRequest.RequestOverride
	if req.SocketAddress.Port != 443 || req.SocketAddress.Scheme != "HTTPS" {
		t.Errorf("https webhook should default to port 443/HTTPS: %#v", req.SocketAddress)
	}
	if req.Secure == nil || !*req.Secure {
		t.Errorf("https webhook should be secure")
	}
	if got := req.Headers["Host"]; len(got) != 1 || got[0] != "hooks.example.com:443" {
		t.Errorf("https webhook Host header wrong: %#v", req.Headers["Host"])
	}
}

func TestA2aMock_Escaping(t *testing.T) {
	// Velocity metacharacters in templated task text must be escaped; the agent
	// card (a literal JSON body) must NOT be velocity-escaped, only JSON-escaped.
	exps := A2aMock("/a2a").
		WithAgentName(`Cost $5 #1 "quote"`).
		OnTaskSend(".*", "price is $5 #1", false).
		Build()

	card := findAgentCard(exps, "/.well-known/agent.json")
	body := card.HttpResponse.Body.(string)
	// JSON-escaped quote, no velocity escaping in the literal agent card.
	if !strings.Contains(body, `"name": "Cost $5 #1 \"quote\""`) {
		t.Errorf("agent card name should be JSON-escaped only (no velocity): %q", body)
	}
	if strings.Contains(body, "${esc.d}") {
		t.Errorf("agent card must not contain velocity escapes: %q", body)
	}
	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(body), &parsed); err != nil {
		t.Fatalf("agent card not valid JSON: %v\n%s", err, body)
	}

	// The templated custom-handler text IS velocity-escaped.
	var handler *Expectation
	for i := range exps {
		if jp, ok := exps[i].HttpRequest.Body.(jsonPathBody); ok && strings.Contains(jp.JSONPath, "tasks/send") {
			handler = &exps[i]
			break
		}
	}
	if handler == nil {
		t.Fatalf("custom handler not found")
	}
	if !strings.Contains(handler.HttpResponseTemplate.Template, `price is ${esc.d}5 ${esc.h}1`) {
		t.Errorf("templated task text should be velocity-escaped: %q", handler.HttpResponseTemplate.Template)
	}
}

func TestA2aMock_CustomAgentCardPath(t *testing.T) {
	exps := A2aMock("/agent").WithAgentCardPath("/.well-known/agent-card.json").Build()
	if findAgentCard(exps, "/.well-known/agent-card.json") == nil {
		t.Errorf("custom agent card path not honoured")
	}
	// JSON-RPC methods are bound to the configured base path.
	send := findByJsonRpcMethod(exps, "tasks/send")
	if send.HttpRequest.Path != "/agent" {
		t.Errorf("tasks/send path = %q, want /agent", send.HttpRequest.Path)
	}
}

func TestA2aMock_InvalidPushUrlPanics(t *testing.T) {
	defer func() {
		if r := recover(); r == nil {
			t.Errorf("expected panic for invalid push-notification URL")
		}
	}()
	A2aMock("/a2a").WithPushNotifications("not a url with no host").Build()
}
