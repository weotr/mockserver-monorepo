package mockserver

import (
	"encoding/json"
	"strings"
	"testing"
)

// marshalExpectations marshals a list of expectations and decodes them back
// into generic maps so individual wire fields can be asserted.
func marshalExpectations(t *testing.T, expectations []Expectation) []map[string]interface{} {
	t.Helper()
	data, err := json.Marshal(expectations)
	if err != nil {
		t.Fatalf("marshal expectations: %v", err)
	}
	var out []map[string]interface{}
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatalf("unmarshal expectations: %v", err)
	}
	return out
}

func TestLlmMock_CompletionWireShape(t *testing.T) {
	exps := LlmMock("/v1/chat/completions").
		WithProvider(ProviderOpenAI).
		WithModel("gpt-4o").
		RespondingWithCompletion(
			NewCompletion().
				WithText("Hello!").
				WithStopReason("stop").
				WithUsage(NewUsage().WithInputTokens(10).WithOutputTokens(5)),
		).
		Build()

	if len(exps) != 1 {
		t.Fatalf("expected 1 expectation, got %d", len(exps))
	}
	got := marshalExpectations(t, exps)[0]

	req, ok := got["httpRequest"].(map[string]interface{})
	if !ok {
		t.Fatalf("missing httpRequest")
	}
	if req["method"] != "POST" || req["path"] != "/v1/chat/completions" {
		t.Errorf("httpRequest = %#v, want POST /v1/chat/completions", req)
	}

	llm, ok := got["httpLlmResponse"].(map[string]interface{})
	if !ok {
		t.Fatalf("missing httpLlmResponse")
	}
	if llm["provider"] != "OPENAI" {
		t.Errorf("provider = %v, want OPENAI (uppercase)", llm["provider"])
	}
	if llm["model"] != "gpt-4o" {
		t.Errorf("model = %v, want gpt-4o", llm["model"])
	}
	comp, ok := llm["completion"].(map[string]interface{})
	if !ok {
		t.Fatalf("missing completion")
	}
	if comp["text"] != "Hello!" {
		t.Errorf("text = %v, want Hello!", comp["text"])
	}
	if _, present := llm["embedding"]; present {
		t.Errorf("embedding should be omitted when completion is set")
	}
	usage, ok := comp["usage"].(map[string]interface{})
	if !ok {
		t.Fatalf("missing usage")
	}
	if usage["inputTokens"].(float64) != 10 || usage["outputTokens"].(float64) != 5 {
		t.Errorf("usage = %#v, want inputTokens 10 / outputTokens 5", usage)
	}
}

func TestLlmMock_EmbeddingClearsCompletion(t *testing.T) {
	exps := LlmMock("/v1/embeddings").
		WithProvider(ProviderOpenAI).
		RespondingWithCompletion(NewCompletion().WithText("ignored")).
		RespondingWithEmbedding(NewEmbedding().WithDimensions(1536).WithDeterministicFromInput(true)).
		Build()

	llm := marshalExpectations(t, exps)[0]["httpLlmResponse"].(map[string]interface{})
	if _, present := llm["completion"]; present {
		t.Errorf("completion should be cleared when embedding is set")
	}
	emb, ok := llm["embedding"].(map[string]interface{})
	if !ok {
		t.Fatalf("missing embedding")
	}
	if emb["dimensions"].(float64) != 1536 {
		t.Errorf("dimensions = %v, want 1536", emb["dimensions"])
	}
	if emb["deterministicFromInput"] != true {
		t.Errorf("deterministicFromInput = %v, want true", emb["deterministicFromInput"])
	}
}

func TestConversation_ScenarioStateAdvancement(t *testing.T) {
	exps := Conversation().
		WithPath("/v1/chat/completions").
		WithProvider(ProviderAnthropic).
		WithModel("claude-3-5-sonnet").
		Turn().
		WhenLatestMessageContains("hello").
		RespondingWith(NewCompletion().WithText("hi there")).
		Turn().
		WhenTurnIndex(1).
		RespondingWith(NewCompletion().WithText("bye")).
		Build()

	if len(exps) != 2 {
		t.Fatalf("expected 2 expectations, got %d", len(exps))
	}
	got := marshalExpectations(t, exps)

	// Scenario name shared across turns and prefixed.
	name0, _ := got[0]["scenarioName"].(string)
	name1, _ := got[1]["scenarioName"].(string)
	if name0 == "" || name0 != name1 {
		t.Errorf("scenarioName mismatch: %q vs %q", name0, name1)
	}
	if !strings.HasPrefix(name0, "__llm_conv_") {
		t.Errorf("scenarioName = %q, want __llm_conv_ prefix", name0)
	}
	if strings.Contains(name0, "__iso=") {
		t.Errorf("scenarioName = %q, should not contain isolation marker", name0)
	}

	if got[0]["scenarioState"] != "Started" {
		t.Errorf("turn 0 scenarioState = %v, want Started", got[0]["scenarioState"])
	}
	if got[0]["newScenarioState"] != "turn_1" {
		t.Errorf("turn 0 newScenarioState = %v, want turn_1", got[0]["newScenarioState"])
	}
	if got[1]["scenarioState"] != "turn_1" {
		t.Errorf("turn 1 scenarioState = %v, want turn_1", got[1]["scenarioState"])
	}
	if got[1]["newScenarioState"] != "__done" {
		t.Errorf("turn 1 newScenarioState = %v, want __done", got[1]["newScenarioState"])
	}

	// Turn 0 has a predicate object; turn 1 has a turnIndex predicate.
	llm0 := got[0]["httpLlmResponse"].(map[string]interface{})
	preds0, ok := llm0["conversationPredicates"].(map[string]interface{})
	if !ok {
		t.Fatalf("turn 0 missing conversationPredicates")
	}
	if preds0["latestMessageContains"] != "hello" {
		t.Errorf("turn 0 latestMessageContains = %v, want hello", preds0["latestMessageContains"])
	}
	llm1 := got[1]["httpLlmResponse"].(map[string]interface{})
	preds1 := llm1["conversationPredicates"].(map[string]interface{})
	if preds1["turnIndex"].(float64) != 1 {
		t.Errorf("turn 1 turnIndex = %v, want 1", preds1["turnIndex"])
	}
}

func TestConversation_NormalizationIsNotAPredicate(t *testing.T) {
	// A turn whose ONLY configuration is normalization must NOT produce a
	// conversationPredicates object.
	exps := Conversation().
		WithPath("/chat").
		WithProvider(ProviderOpenAI).
		Turn().
		WithNormalization(&NormalizationOptions{Lowercase: boolPtr(true)}).
		RespondingWith(NewCompletion().WithText("x")).
		Build()

	llm := marshalExpectations(t, exps)[0]["httpLlmResponse"].(map[string]interface{})
	if _, present := llm["conversationPredicates"]; present {
		t.Errorf("normalization-only turn should not emit conversationPredicates")
	}
}

func TestConversation_IsolationSuffix(t *testing.T) {
	exps := Conversation().
		WithPath("/chat").
		WithProvider(ProviderOpenAI).
		IsolateBy(IsolateByHeader("x-session-id")).
		Turn().
		RespondingWith(NewCompletion().WithText("x")).
		Build()

	name := marshalExpectations(t, exps)[0]["scenarioName"].(string)
	if !strings.Contains(name, "__iso=header:x-session-id") {
		t.Errorf("scenarioName = %q, want __iso=header:x-session-id suffix", name)
	}
}

func TestLlmFailover_CoalescingAndDefaultBodies(t *testing.T) {
	exps := LlmFailover().
		WithPath("/v1/chat/completions").
		WithProvider(ProviderOpenAI).
		WithModel("gpt-4o").
		FailWithCount(429, 2).
		FailWith(500).
		ThenRespondWith(NewCompletion().WithText("recovered")).
		Build()

	// 429x2 coalesces into one; 500 into one; plus the success = 3 expectations.
	if len(exps) != 3 {
		t.Fatalf("expected 3 expectations, got %d", len(exps))
	}
	got := marshalExpectations(t, exps)

	// First failure: 429 coalesced, remainingTimes 2, default rate_limit body.
	times0 := got[0]["times"].(map[string]interface{})
	if times0["remainingTimes"].(float64) != 2 {
		t.Errorf("first failure remainingTimes = %v, want 2", times0["remainingTimes"])
	}
	if times0["unlimited"] != false {
		t.Errorf("first failure unlimited = %v, want false", times0["unlimited"])
	}
	resp0 := got[0]["httpResponse"].(map[string]interface{})
	if resp0["statusCode"].(float64) != 429 {
		t.Errorf("first failure statusCode = %v, want 429", resp0["statusCode"])
	}
	body0 := resp0["body"].(string)
	if !strings.Contains(body0, `"type":"rate_limit_error"`) {
		t.Errorf("429 body = %q, want rate_limit_error", body0)
	}
	if !strings.Contains(body0, "Rate limit exceeded. Please retry after a brief wait.") {
		t.Errorf("429 body missing default message: %q", body0)
	}

	// Second failure: 500, remainingTimes 1, default internal_server_error body.
	times1 := got[1]["times"].(map[string]interface{})
	if times1["remainingTimes"].(float64) != 1 {
		t.Errorf("second failure remainingTimes = %v, want 1", times1["remainingTimes"])
	}
	resp1 := got[1]["httpResponse"].(map[string]interface{})
	if resp1["statusCode"].(float64) != 500 {
		t.Errorf("second failure statusCode = %v, want 500", resp1["statusCode"])
	}
	if !strings.Contains(resp1["body"].(string), `"type":"internal_server_error"`) {
		t.Errorf("500 body = %q, want internal_server_error", resp1["body"])
	}

	// Success expectation: unlimited times + httpLlmResponse completion.
	timesS := got[2]["times"].(map[string]interface{})
	if timesS["unlimited"] != true {
		t.Errorf("success unlimited = %v, want true", timesS["unlimited"])
	}
	llmS, ok := got[2]["httpLlmResponse"].(map[string]interface{})
	if !ok {
		t.Fatalf("success expectation missing httpLlmResponse")
	}
	if llmS["provider"] != "OPENAI" {
		t.Errorf("success provider = %v, want OPENAI", llmS["provider"])
	}
	compS := llmS["completion"].(map[string]interface{})
	if compS["text"] != "recovered" {
		t.Errorf("success completion text = %v, want recovered", compS["text"])
	}
}

func TestLlmFailover_CustomBodyNotCoalescedWithDefault(t *testing.T) {
	exps := LlmFailover().
		WithPath("/chat").
		WithProvider(ProviderOpenAI).
		FailWith(429).
		FailWithBody(429, `{"custom":true}`).
		ThenRespondWith(NewCompletion().WithText("ok")).
		Build()

	// 429-default and 429-custom differ, so they must NOT coalesce: 2 failures + success.
	if len(exps) != 3 {
		t.Fatalf("expected 3 expectations (no coalesce across differing bodies), got %d", len(exps))
	}
	got := marshalExpectations(t, exps)
	if got[1]["httpResponse"].(map[string]interface{})["body"] != `{"custom":true}` {
		t.Errorf("custom body not preserved: %v", got[1]["httpResponse"])
	}
}

func TestDefaultErrorBody_AllStatuses(t *testing.T) {
	cases := map[int]string{
		429: "rate_limit_error",
		500: "internal_server_error",
		502: "bad_gateway",
		503: "service_unavailable",
		418: "error",
	}
	for code, wantType := range cases {
		body := defaultErrorBody(code)
		if !strings.Contains(body, `"type":"`+wantType+`"`) {
			t.Errorf("defaultErrorBody(%d) = %q, want type %q", code, body, wantType)
		}
	}
	if !strings.Contains(defaultErrorBody(418), "Request failed with status 418") {
		t.Errorf("default fallback message missing for 418")
	}
}

func boolPtr(b bool) *bool { return &b }
