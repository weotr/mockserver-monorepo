//! Typed stateful-scenario tests.
//!
//! Two kinds of tests, no running MockServer required:
//!  * serde shape tests proving the new `Expectation` scenario fields and the
//!    `CrossProtocolScenario` type serialize to the exact JSON of the scenario
//!    parity contract, and
//!  * stub-server tests using `tiny_http` (a dev-dependency) that assert the
//!    exact HTTP method, path and body each `client.scenario(..)` /
//!    `client.scenarios()` call sends, and that status codes map correctly.

use std::sync::mpsc;
use std::thread;

use mockserver_client::*;

// ---------------------------------------------------------------------------
// Stub HTTP server harness (mirrors tests/sre_tests.rs)
// ---------------------------------------------------------------------------

/// What the stub captured about the single request it served.
struct Captured {
    method: String,
    url: String,
    body: String,
}

/// Start a one-shot stub server that replies with `status`/`resp_body` to the
/// first request, capturing its method, url and body. Returns a client pointed
/// at the stub and a receiver that yields the captured request.
fn stub(status: u16, resp_body: &'static str) -> (MockServerClient, mpsc::Receiver<Captured>) {
    let server = tiny_http::Server::http("127.0.0.1:0").expect("bind stub server");
    let port = match server.server_addr() {
        tiny_http::ListenAddr::IP(addr) => addr.port(),
        #[allow(unreachable_patterns)]
        _ => panic!("expected IP listen address"),
    };
    let (tx, rx) = mpsc::channel();

    thread::spawn(move || {
        let mut request = server.recv().expect("stub recv");
        let method = request.method().as_str().to_string();
        let url = request.url().to_string();
        let mut body = String::new();
        request.as_reader().read_to_string(&mut body).ok();
        tx.send(Captured { method, url, body }).ok();

        let response = tiny_http::Response::from_string(resp_body)
            .with_status_code(tiny_http::StatusCode(status));
        request.respond(response).ok();
    });

    let client = ClientBuilder::new("127.0.0.1", port)
        .build()
        .expect("build client");
    (client, rx)
}

// ---------------------------------------------------------------------------
// Expectation scenario fields — serde shape (contract section 1)
// ---------------------------------------------------------------------------

#[test]
fn test_scenario_state_fields_serialize_to_contract_names() {
    let exp = Expectation::new(HttpRequest::new().method("GET").path("/status"))
        .scenario_name("Deploy")
        .scenario_state("Deploying")
        .new_scenario_state("Deployed")
        .respond(HttpResponse::new().status_code(200));
    let json = serde_json::to_value(&exp).unwrap();

    assert_eq!(json["scenarioName"], "Deploy");
    assert_eq!(json["scenarioState"], "Deploying");
    assert_eq!(json["newScenarioState"], "Deployed");
}

#[test]
fn test_multiple_responses_and_response_mode_sequential() {
    let exp = Expectation::new(HttpRequest::new().path("/poll"))
        .respond_with(HttpResponse::new().status_code(202).body("pending"))
        .respond_with(HttpResponse::new().status_code(200).body("done"))
        .response_mode(ResponseMode::Sequential);
    let json = serde_json::to_value(&exp).unwrap();

    assert_eq!(json["responseMode"], "SEQUENTIAL");
    let responses = json["httpResponses"].as_array().unwrap();
    assert_eq!(responses.len(), 2);
    assert_eq!(responses[0]["statusCode"], 202);
    assert_eq!(responses[0]["body"], "pending");
    assert_eq!(responses[1]["statusCode"], 200);
    assert_eq!(responses[1]["body"], "done");
}

#[test]
fn test_response_mode_variants_serialize_to_screaming_snake() {
    for (mode, expected) in [
        (ResponseMode::Sequential, "SEQUENTIAL"),
        (ResponseMode::Random, "RANDOM"),
        (ResponseMode::Weighted, "WEIGHTED"),
        (ResponseMode::Switch, "SWITCH"),
    ] {
        let json = serde_json::to_value(mode).unwrap();
        assert_eq!(json, serde_json::json!(expected));
    }
}

#[test]
fn test_weighted_response_mode_with_weights() {
    let exp = Expectation::new(HttpRequest::new().path("/ab"))
        .http_responses(vec![
            HttpResponse::new().status_code(200).body("a"),
            HttpResponse::new().status_code(500).body("b"),
        ])
        .response_mode(ResponseMode::Weighted)
        .response_weights(vec![9, 1]);
    let json = serde_json::to_value(&exp).unwrap();

    assert_eq!(json["responseMode"], "WEIGHTED");
    assert_eq!(json["responseWeights"], serde_json::json!([9, 1]));
}

#[test]
fn test_switch_after() {
    let exp = Expectation::new(HttpRequest::new().path("/switch"))
        .response_mode(ResponseMode::Switch)
        .switch_after(3);
    let json = serde_json::to_value(&exp).unwrap();

    assert_eq!(json["responseMode"], "SWITCH");
    assert_eq!(json["switchAfter"], 3);
}

#[test]
fn test_cross_protocol_scenario_full_shape() {
    let scenario = CrossProtocolScenario::new(CrossProtocolTrigger::DnsQuery, "Deploy", "DnsObserved")
        .match_pattern("api.example.com");
    let exp = Expectation::new(HttpRequest::new().path("/x"))
        .cross_protocol_scenario(scenario);
    let json = serde_json::to_value(&exp).unwrap();

    let arr = json["crossProtocolScenarios"].as_array().unwrap();
    assert_eq!(arr.len(), 1);
    assert_eq!(arr[0]["trigger"], "DNS_QUERY");
    assert_eq!(arr[0]["matchPattern"], "api.example.com");
    assert_eq!(arr[0]["scenarioName"], "Deploy");
    assert_eq!(arr[0]["targetState"], "DnsObserved");
}

#[test]
fn test_cross_protocol_trigger_variants() {
    for (trigger, expected) in [
        (CrossProtocolTrigger::DnsQuery, "DNS_QUERY"),
        (CrossProtocolTrigger::WebsocketConnect, "WEBSOCKET_CONNECT"),
        (CrossProtocolTrigger::GrpcRequest, "GRPC_REQUEST"),
        (CrossProtocolTrigger::HttpRequest, "HTTP_REQUEST"),
    ] {
        let json = serde_json::to_value(trigger).unwrap();
        assert_eq!(json, serde_json::json!(expected));
    }
}

#[test]
fn test_cross_protocol_scenario_omits_match_pattern_when_unset() {
    let scenario =
        CrossProtocolScenario::new(CrossProtocolTrigger::WebsocketConnect, "Deploy", "WsObserved");
    let json = serde_json::to_value(&scenario).unwrap();

    assert!(json.get("matchPattern").is_none());
    assert_eq!(json["trigger"], "WEBSOCKET_CONNECT");
    assert_eq!(json["scenarioName"], "Deploy");
    assert_eq!(json["targetState"], "WsObserved");
}

#[test]
fn test_scenario_fields_omitted_when_unset() {
    // Backward-compatibility: an expectation with no scenario fields set must
    // not emit any of the new keys.
    let exp = Expectation::new(HttpRequest::new().path("/plain"))
        .respond(HttpResponse::new().status_code(200));
    let json = serde_json::to_value(&exp).unwrap();

    for key in [
        "scenarioName",
        "scenarioState",
        "newScenarioState",
        "httpResponses",
        "responseMode",
        "responseWeights",
        "switchAfter",
        "crossProtocolScenarios",
    ] {
        assert!(json.get(key).is_none(), "unexpected key {key} serialized");
    }
}

#[test]
fn test_expectation_scenario_round_trip() {
    let exp = Expectation::new(HttpRequest::new().path("/rt"))
        .scenario_name("S")
        .scenario_state("A")
        .new_scenario_state("B")
        .response_mode(ResponseMode::Random)
        .response_weights(vec![1, 2])
        .switch_after(2)
        .cross_protocol_scenario(CrossProtocolScenario::new(
            CrossProtocolTrigger::HttpRequest,
            "S",
            "B",
        ));
    let json = serde_json::to_string(&exp).unwrap();
    let back: Expectation = serde_json::from_str(&json).unwrap();
    assert_eq!(exp, back);
}

// ---------------------------------------------------------------------------
// ScenarioState deserialization (contract section 2 response shapes)
// ---------------------------------------------------------------------------

#[test]
fn test_scenario_state_deserializes_from_contract_json() {
    let state: ScenarioState =
        serde_json::from_str(r#"{"scenarioName":"Deploy","currentState":"Deploying"}"#).unwrap();
    assert_eq!(state.scenario_name, "Deploy");
    assert_eq!(state.current_state, "Deploying");
}

// ---------------------------------------------------------------------------
// Scenario helper — HTTP method/path/body shape (stub server)
// ---------------------------------------------------------------------------

#[test]
fn test_scenario_state_sends_get_to_named_path() {
    let (client, rx) = stub(200, r#"{"scenarioName":"Deploy","currentState":"Deploying"}"#);
    let state = client.scenario("Deploy").state().unwrap();
    assert_eq!(state, "Deploying");

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/scenario/Deploy");
}

#[test]
fn test_scenario_set_sends_put_with_state_body() {
    let (client, rx) = stub(200, r#"{"scenarioName":"Deploy","currentState":"Deploying"}"#);
    client.scenario("Deploy").set("Deploying").unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/scenario/Deploy");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["state"], "Deploying");
    assert!(sent.get("transitionAfterMs").is_none());
    assert!(sent.get("nextState").is_none());
}

#[test]
fn test_scenario_set_timed_sends_transition_fields() {
    let (client, rx) = stub(
        200,
        r#"{"scenarioName":"Deploy","currentState":"Deploying","nextState":"Deployed","transitionAfterMs":5000}"#,
    );
    client
        .scenario("Deploy")
        .set_timed("Deploying", 5000, "Deployed")
        .unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/scenario/Deploy");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["state"], "Deploying");
    assert_eq!(sent["transitionAfterMs"], 5000);
    assert_eq!(sent["nextState"], "Deployed");
}

#[test]
fn test_scenario_trigger_sends_put_to_trigger_path() {
    let (client, rx) = stub(200, r#"{"scenarioName":"Deploy","currentState":"Failed"}"#);
    client.scenario("Deploy").trigger("Failed").unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/scenario/Deploy/trigger");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["newState"], "Failed");
}

#[test]
fn test_scenario_name_is_percent_encoded_in_path() {
    // A scenario name containing reserved characters must be percent-encoded as a
    // single path segment (parity with the other clients), so it cannot split the
    // path or be read as a query.
    let (client, rx) = stub(200, r#"{"scenarioName":"order/v2","currentState":"Ready"}"#);
    client.scenario("order/v2").state().unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/scenario/order%2Fv2");
}

#[test]
fn test_scenarios_list_sends_get_and_parses_array() {
    let (client, rx) = stub(
        200,
        r#"{"scenarios":[{"scenarioName":"A","currentState":"x"},{"scenarioName":"B","currentState":"y"}]}"#,
    );
    let scenarios = client.scenarios().unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/scenario");

    assert_eq!(scenarios.len(), 2);
    assert_eq!(scenarios[0].scenario_name, "A");
    assert_eq!(scenarios[0].current_state, "x");
    assert_eq!(scenarios[1].scenario_name, "B");
    assert_eq!(scenarios[1].current_state, "y");
}

#[test]
fn test_scenario_set_400_is_invalid_request() {
    let (client, _rx) = stub(400, r#"{"error":"'state' field is required"}"#);
    let err = client.scenario("Deploy").set("").unwrap_err();
    assert!(matches!(err, Error::InvalidRequest(_)));
}
