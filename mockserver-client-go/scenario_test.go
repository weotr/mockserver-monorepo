package mockserver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// --- Expectation scenario-field marshalling tests ---

func TestExpectation_ScenarioFields_JSON(t *testing.T) {
	switchAfter := 3
	exp := Expectation{
		ScenarioName:     "Deploy",
		ScenarioState:    "Deploying",
		NewScenarioState: "Deployed",
		ResponseMode:     ResponseModeWeighted,
		ResponseWeights:  []int{1, 2, 3},
		SwitchAfter:      &switchAfter,
		HttpResponses: []*HttpResponse{
			{StatusCode: 200},
			{StatusCode: 503},
		},
		CrossProtocolScenarios: []CrossProtocolScenario{
			{
				Trigger:      CrossProtocolTriggerDNSQuery,
				MatchPattern: "api.example.com",
				ScenarioName: "Deploy",
				TargetState:  "Failed",
			},
		},
	}

	data, err := json.Marshal(exp)
	if err != nil {
		t.Fatal(err)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}

	assertField := func(key string, want interface{}) {
		t.Helper()
		got, ok := m[key]
		if !ok {
			t.Errorf("expected field %q in JSON, missing; json=%s", key, data)
			return
		}
		if want != nil && got != want {
			t.Errorf("field %q: expected %v, got %v", key, want, got)
		}
	}

	assertField("scenarioName", "Deploy")
	assertField("scenarioState", "Deploying")
	assertField("newScenarioState", "Deployed")
	assertField("responseMode", "WEIGHTED")
	assertField("switchAfter", float64(3))
	assertField("responseWeights", nil)
	assertField("httpResponses", nil)
	assertField("crossProtocolScenarios", nil)

	weights, ok := m["responseWeights"].([]interface{})
	if !ok || len(weights) != 3 || weights[0] != float64(1) {
		t.Errorf("responseWeights wrong: %v", m["responseWeights"])
	}

	responses, ok := m["httpResponses"].([]interface{})
	if !ok || len(responses) != 2 {
		t.Fatalf("httpResponses wrong: %v", m["httpResponses"])
	}

	cps, ok := m["crossProtocolScenarios"].([]interface{})
	if !ok || len(cps) != 1 {
		t.Fatalf("crossProtocolScenarios wrong: %v", m["crossProtocolScenarios"])
	}
	cp := cps[0].(map[string]interface{})
	if cp["trigger"] != "DNS_QUERY" {
		t.Errorf("trigger: expected DNS_QUERY, got %v", cp["trigger"])
	}
	if cp["matchPattern"] != "api.example.com" {
		t.Errorf("matchPattern: expected api.example.com, got %v", cp["matchPattern"])
	}
	if cp["scenarioName"] != "Deploy" {
		t.Errorf("scenarioName: expected Deploy, got %v", cp["scenarioName"])
	}
	if cp["targetState"] != "Failed" {
		t.Errorf("targetState: expected Failed, got %v", cp["targetState"])
	}
}

func TestExpectation_ScenarioFields_OmittedWhenUnset(t *testing.T) {
	exp := Expectation{HttpRequest: &HttpRequest{}}
	data, err := json.Marshal(exp)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{
		"scenarioName", "scenarioState", "newScenarioState",
		"responseMode", "responseWeights", "switchAfter",
		"httpResponses", "crossProtocolScenarios",
	} {
		if _, ok := m[key]; ok {
			t.Errorf("field %q should be omitted when unset, got %v", key, m[key])
		}
	}
}

func TestCrossProtocolScenario_RequiredFieldsAlwaysEmitted(t *testing.T) {
	// trigger, scenarioName and targetState have no omitempty — they must
	// always serialize even when empty.
	data, err := json.Marshal(CrossProtocolScenario{})
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"trigger", "scenarioName", "targetState"} {
		if _, ok := m[key]; !ok {
			t.Errorf("field %q must always be emitted; json=%s", key, data)
		}
	}
	if _, ok := m["matchPattern"]; ok {
		t.Errorf("matchPattern should be omitted when empty; json=%s", data)
	}
}

// --- ForwardChainExpectation builder tests ---

func TestRespondMultiple_BuildsHttpResponses(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.
		When(Request().Method("GET").Path("/hello")).
		WithResponseMode(ResponseModeSwitch).
		WithSwitchAfter(2).
		RespondMultiple(
			Response().StatusCode(200),
			Response().StatusCode(503),
		)
	if err != nil {
		t.Fatal(err)
	}

	var exps []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &exps); err != nil {
		t.Fatalf("body not valid JSON: %s", err)
	}
	if len(exps) != 1 {
		t.Fatalf("expected 1 expectation, got %d", len(exps))
	}
	exp := exps[0]
	if exp["responseMode"] != "SWITCH" {
		t.Errorf("responseMode: expected SWITCH, got %v", exp["responseMode"])
	}
	if exp["switchAfter"] != float64(2) {
		t.Errorf("switchAfter: expected 2, got %v", exp["switchAfter"])
	}
	responses, ok := exp["httpResponses"].([]interface{})
	if !ok || len(responses) != 2 {
		t.Fatalf("httpResponses wrong: %v", exp["httpResponses"])
	}
}

func TestWithCrossProtocolScenario_Appends(t *testing.T) {
	client := NewFromURL("http://example.invalid")
	fce := client.
		When(Request().Method("GET").Path("/hello")).
		WithCrossProtocolScenario(CrossProtocolScenario{
			Trigger:      CrossProtocolTriggerHTTPRequest,
			ScenarioName: "S",
			TargetState:  "T1",
		}).
		WithCrossProtocolScenario(CrossProtocolScenario{
			Trigger:      CrossProtocolTriggerGrpcRequest,
			ScenarioName: "S",
			TargetState:  "T2",
		})
	if len(fce.expectation.CrossProtocolScenarios) != 2 {
		t.Fatalf("expected 2 cross-protocol scenarios, got %d", len(fce.expectation.CrossProtocolScenarios))
	}
}

// --- Scenario REST helper tests ---

func TestScenario_State_GET(t *testing.T) {
	var receivedPath, receivedMethod string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedMethod = r.Method
		w.WriteHeader(200)
		w.Write([]byte(`{"scenarioName":"Deploy","currentState":"Deploying"}`))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	state, err := client.Scenario("Deploy").State()
	if err != nil {
		t.Fatal(err)
	}
	if state != "Deploying" {
		t.Errorf("expected state Deploying, got %q", state)
	}
	if receivedMethod != "GET" {
		t.Errorf("expected GET, got %s", receivedMethod)
	}
	if receivedPath != "/mockserver/scenario/Deploy" {
		t.Errorf("expected /mockserver/scenario/Deploy, got %s", receivedPath)
	}
}

func TestScenario_Set_PUT(t *testing.T) {
	var receivedPath, receivedMethod string
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedMethod = r.Method
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(200)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.Scenario("Deploy").Set("Deploying"); err != nil {
		t.Fatal(err)
	}
	if receivedMethod != "PUT" {
		t.Errorf("expected PUT, got %s", receivedMethod)
	}
	if receivedPath != "/mockserver/scenario/Deploy" {
		t.Errorf("expected /mockserver/scenario/Deploy, got %s", receivedPath)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(receivedBody, &m); err != nil {
		t.Fatal(err)
	}
	if m["state"] != "Deploying" {
		t.Errorf("expected state Deploying, got %v", m["state"])
	}
	if _, ok := m["transitionAfterMs"]; ok {
		t.Errorf("Set should not include transitionAfterMs; body=%s", receivedBody)
	}
}

func TestScenario_SetTimed_PUT(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(200)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.Scenario("Deploy").SetTimed("Deploying", 5000, "Deployed"); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(receivedBody, &m); err != nil {
		t.Fatal(err)
	}
	if m["state"] != "Deploying" {
		t.Errorf("expected state Deploying, got %v", m["state"])
	}
	if m["transitionAfterMs"] != float64(5000) {
		t.Errorf("expected transitionAfterMs 5000, got %v", m["transitionAfterMs"])
	}
	if m["nextState"] != "Deployed" {
		t.Errorf("expected nextState Deployed, got %v", m["nextState"])
	}
}

func TestScenario_Trigger_PUT(t *testing.T) {
	var receivedPath, receivedMethod string
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedMethod = r.Method
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(200)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.Scenario("Deploy").Trigger("Failed"); err != nil {
		t.Fatal(err)
	}
	if receivedMethod != "PUT" {
		t.Errorf("expected PUT, got %s", receivedMethod)
	}
	if receivedPath != "/mockserver/scenario/Deploy/trigger" {
		t.Errorf("expected /mockserver/scenario/Deploy/trigger, got %s", receivedPath)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(receivedBody, &m); err != nil {
		t.Fatal(err)
	}
	if m["newState"] != "Failed" {
		t.Errorf("expected newState Failed, got %v", m["newState"])
	}
}

func TestScenarios_List_GET(t *testing.T) {
	var receivedPath, receivedMethod string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedMethod = r.Method
		w.WriteHeader(200)
		w.Write([]byte(`{"scenarios":[{"scenarioName":"Deploy","currentState":"Deploying"},{"scenarioName":"Rollout","currentState":"Idle"}]}`))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	scenarios, err := client.Scenarios()
	if err != nil {
		t.Fatal(err)
	}
	if receivedMethod != "GET" {
		t.Errorf("expected GET, got %s", receivedMethod)
	}
	if receivedPath != "/mockserver/scenario" {
		t.Errorf("expected /mockserver/scenario, got %s", receivedPath)
	}
	if len(scenarios) != 2 {
		t.Fatalf("expected 2 scenarios, got %d", len(scenarios))
	}
	if scenarios[0].ScenarioName != "Deploy" || scenarios[0].CurrentState != "Deploying" {
		t.Errorf("scenario[0] wrong: %+v", scenarios[0])
	}
	if scenarios[1].ScenarioName != "Rollout" || scenarios[1].CurrentState != "Idle" {
		t.Errorf("scenario[1] wrong: %+v", scenarios[1])
	}
}

func TestScenario_State_ErrorStatus(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
		w.Write([]byte("not found"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if _, err := client.Scenario("Missing").State(); err == nil {
		t.Error("expected error for 404 status, got nil")
	}
}
