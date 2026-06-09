//! Unit tests — JSON building and model construction.
//! These run with NO server.

use mockserver_client::*;

// ---------------------------------------------------------------------------
// HttpRequest builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_empty_request_serializes_to_empty_object() {
    let req = HttpRequest::new();
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json, serde_json::json!({}));
}

#[test]
fn test_request_method_and_path() {
    let req = HttpRequest::new().method("GET").path("/hello");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["method"], "GET");
    assert_eq!(json["path"], "/hello");
}

#[test]
fn test_request_query_params() {
    let req = HttpRequest::new()
        .path("/search")
        .query_param("q", "rust")
        .query_param("page", "1");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["queryStringParameters"]["q"], serde_json::json!(["rust"]));
    assert_eq!(json["queryStringParameters"]["page"], serde_json::json!(["1"]));
}

#[test]
fn test_request_multiple_values_same_key() {
    let req = HttpRequest::new()
        .query_param("tag", "a")
        .query_param("tag", "b");
    let json = serde_json::to_value(&req).unwrap();
    let tags = json["queryStringParameters"]["tag"].as_array().unwrap();
    assert_eq!(tags.len(), 2);
    assert!(tags.contains(&serde_json::json!("a")));
    assert!(tags.contains(&serde_json::json!("b")));
}

#[test]
fn test_request_headers() {
    let req = HttpRequest::new()
        .header("Content-Type", "application/json")
        .header("Accept", "text/plain");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["headers"]["Content-Type"],
        serde_json::json!(["application/json"])
    );
    assert_eq!(
        json["headers"]["Accept"],
        serde_json::json!(["text/plain"])
    );
}

#[test]
fn test_request_plain_body() {
    let req = HttpRequest::new().body("hello world");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"], "hello world");
}

#[test]
fn test_request_json_body() {
    let req = HttpRequest::new().json_body(serde_json::json!({"key": "value"}));
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "JSON");
    // The json field is the serialized string of the value
    let json_str: &str = json["body"]["json"].as_str().unwrap();
    let parsed: serde_json::Value = serde_json::from_str(json_str).unwrap();
    assert_eq!(parsed, serde_json::json!({"key": "value"}));
}

// ---------------------------------------------------------------------------
// HttpResponse builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_response_status_code() {
    let resp = HttpResponse::new().status_code(200);
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["statusCode"], 200);
}

#[test]
fn test_response_with_body_and_headers() {
    let resp = HttpResponse::new()
        .status_code(201)
        .header("Location", "/api/42")
        .body("{\"id\": 42}");
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["statusCode"], 201);
    assert_eq!(json["body"], "{\"id\": 42}");
    assert_eq!(
        json["headers"]["Location"],
        serde_json::json!(["/api/42"])
    );
}

#[test]
fn test_response_with_delay() {
    let resp = HttpResponse::new()
        .status_code(200)
        .delay(Delay::milliseconds(500));
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["delay"]["value"], 500);
}

// ---------------------------------------------------------------------------
// HttpForward builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_forward_basic() {
    let fwd = HttpForward::new("backend.local", 8080);
    let json = serde_json::to_value(&fwd).unwrap();
    assert_eq!(json["host"], "backend.local");
    assert_eq!(json["port"], 8080);
    assert!(json.get("scheme").is_none());
}

#[test]
fn test_forward_with_scheme() {
    let fwd = HttpForward::new("secure.local", 443).scheme("HTTPS");
    let json = serde_json::to_value(&fwd).unwrap();
    assert_eq!(json["scheme"], "HTTPS");
}

// ---------------------------------------------------------------------------
// HttpError builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_error_drop_connection() {
    let err = HttpError::new().drop_connection(true);
    let json = serde_json::to_value(&err).unwrap();
    assert_eq!(json["dropConnection"], true);
}

// ---------------------------------------------------------------------------
// Times and TimeToLive tests
// ---------------------------------------------------------------------------

#[test]
fn test_times_unlimited() {
    let t = Times::unlimited();
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["unlimited"], true);
    assert!(json.get("remainingTimes").is_none());
}

#[test]
fn test_times_exactly() {
    let t = Times::exactly(3);
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["unlimited"], false);
    assert_eq!(json["remainingTimes"], 3);
}

#[test]
fn test_times_once() {
    let t = Times::once();
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["remainingTimes"], 1);
    assert_eq!(json["unlimited"], false);
}

#[test]
fn test_ttl_unlimited() {
    let ttl = TimeToLive::unlimited();
    let json = serde_json::to_value(&ttl).unwrap();
    assert_eq!(json["unlimited"], true);
    assert!(json.get("timeUnit").is_none());
}

#[test]
fn test_ttl_seconds() {
    let ttl = TimeToLive::seconds(30);
    let json = serde_json::to_value(&ttl).unwrap();
    assert_eq!(json["unlimited"], false);
    assert_eq!(json["timeUnit"], "SECONDS");
    assert_eq!(json["timeToLive"], 30);
}

// ---------------------------------------------------------------------------
// VerificationTimes tests
// ---------------------------------------------------------------------------

#[test]
fn test_verification_times_at_least() {
    let vt = VerificationTimes::at_least(2);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 2);
    assert!(json.get("atMost").is_none());
}

#[test]
fn test_verification_times_exactly() {
    let vt = VerificationTimes::exactly(5);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 5);
    assert_eq!(json["atMost"], 5);
}

#[test]
fn test_verification_times_between() {
    let vt = VerificationTimes::between(1, 10);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 1);
    assert_eq!(json["atMost"], 10);
}

// ---------------------------------------------------------------------------
// Full Expectation tests
// ---------------------------------------------------------------------------

#[test]
fn test_full_expectation_json() {
    let expectation = Expectation::new(
        HttpRequest::new()
            .method("GET")
            .path("/hello")
            .query_param("q", "x")
            .header("H", "v"),
    )
    .respond(
        HttpResponse::new()
            .status_code(200)
            .header("Content-Type", "application/json")
            .body("{\"k\":1}")
            .delay(Delay::milliseconds(0)),
    )
    .times(Times::once())
    .time_to_live(TimeToLive::unlimited())
    .priority(0);

    let json = serde_json::to_value(&expectation).unwrap();

    // Verify request
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/hello");
    assert_eq!(json["httpRequest"]["queryStringParameters"]["q"], serde_json::json!(["x"]));
    assert_eq!(json["httpRequest"]["headers"]["H"], serde_json::json!(["v"]));

    // Verify response
    assert_eq!(json["httpResponse"]["statusCode"], 200);
    assert_eq!(json["httpResponse"]["body"], "{\"k\":1}");
    assert_eq!(
        json["httpResponse"]["headers"]["Content-Type"],
        serde_json::json!(["application/json"])
    );
    assert_eq!(json["httpResponse"]["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["httpResponse"]["delay"]["value"], 0);

    // Verify times
    assert_eq!(json["times"]["remainingTimes"], 1);
    assert_eq!(json["times"]["unlimited"], false);

    // Verify TTL
    assert_eq!(json["timeToLive"]["unlimited"], true);

    // Verify priority
    assert_eq!(json["priority"], 0);
}

#[test]
fn test_expectation_with_forward() {
    let expectation = Expectation::new(HttpRequest::new().method("GET").path("/proxy"))
        .forward(HttpForward::new("backend", 8080).scheme("HTTP"));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpForward"]["host"], "backend");
    assert_eq!(json["httpForward"]["port"], 8080);
    assert_eq!(json["httpForward"]["scheme"], "HTTP");
    assert!(json.get("httpResponse").is_none());
}

#[test]
fn test_expectation_with_error() {
    let expectation = Expectation::new(HttpRequest::new().path("/fail"))
        .error(HttpError::new().drop_connection(true));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpError"]["dropConnection"], true);
    assert!(json.get("httpResponse").is_none());
    assert!(json.get("httpForward").is_none());
}

#[test]
fn test_expectation_with_id() {
    let expectation = Expectation::new(HttpRequest::new().path("/x"))
        .id("my-expectation-1")
        .respond(HttpResponse::new().status_code(204));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["id"], "my-expectation-1");
}

// ---------------------------------------------------------------------------
// Verification JSON tests
// ---------------------------------------------------------------------------

#[test]
fn test_verification_json() {
    let v = Verification {
        http_request: HttpRequest::new().method("GET").path("/hello"),
        times: Some(VerificationTimes::at_least(1)),
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/hello");
    assert_eq!(json["times"]["atLeast"], 1);
}

#[test]
fn test_verification_sequence_json() {
    let vs = VerificationSequence {
        http_requests: vec![
            HttpRequest::new().path("/first"),
            HttpRequest::new().path("/second"),
        ],
    };
    let json = serde_json::to_value(&vs).unwrap();
    let requests = json["httpRequests"].as_array().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0]["path"], "/first");
    assert_eq!(requests[1]["path"], "/second");
}

// ---------------------------------------------------------------------------
// Ports JSON tests
// ---------------------------------------------------------------------------

#[test]
fn test_ports_serialization() {
    let p = Ports {
        ports: vec![1080, 1081],
    };
    let json = serde_json::to_value(&p).unwrap();
    assert_eq!(json["ports"], serde_json::json!([1080, 1081]));
}

#[test]
fn test_ports_deserialization() {
    let json = r#"{"ports":[1080,1081]}"#;
    let p: Ports = serde_json::from_str(json).unwrap();
    assert_eq!(p.ports, vec![1080, 1081]);
}

// ---------------------------------------------------------------------------
// Body deserialization tests
// ---------------------------------------------------------------------------

#[test]
fn test_body_plain_deserialization() {
    let json = r#"{"method":"GET","path":"/x","body":"hello"}"#;
    let req: HttpRequest = serde_json::from_str(json).unwrap();
    assert_eq!(req.body, Some(Body::Plain("hello".to_string())));
}

#[test]
fn test_body_typed_deserialization() {
    let json = r#"{"body":{"type":"JSON","json":"{\"key\":\"value\"}"}}"#;
    let req: HttpRequest = serde_json::from_str(json).unwrap();
    match req.body {
        Some(Body::Typed { body_type, json }) => {
            assert_eq!(body_type, "JSON");
            assert_eq!(json, "{\"key\":\"value\"}");
        }
        other => panic!("Expected Body::Typed, got {:?}", other),
    }
}

// ---------------------------------------------------------------------------
// ClientBuilder URL construction tests
// ---------------------------------------------------------------------------

#[test]
fn test_client_builder_default() {
    // Builder succeeds and produces a valid client
    let client = ClientBuilder::new("localhost", 1080).build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_with_context_path() {
    // Context path with leading slash
    let client = ClientBuilder::new("myhost", 9090)
        .context_path("/api")
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_context_path_without_slash() {
    // Context path without leading slash gets one prepended
    let client = ClientBuilder::new("myhost", 8080)
        .context_path("mock")
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_secure() {
    let client = ClientBuilder::new("secure.local", 443)
        .secure(true)
        .tls_verify(false)
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_connection_refused() {
    // Connecting to a port nothing listens on should produce a transport error
    let client = ClientBuilder::new("127.0.0.1", 19999).build().unwrap();
    let result = client.status();
    assert!(result.is_err());
    let err_msg = format!("{}", result.unwrap_err());
    assert!(
        err_msg.contains("transport") || err_msg.contains("error") || err_msg.contains("Connection refused"),
        "Should get a transport error: {err_msg}"
    );
}

// ---------------------------------------------------------------------------
// Delay tests
// ---------------------------------------------------------------------------

#[test]
fn test_delay_seconds() {
    let d = Delay::seconds(5);
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["timeUnit"], "SECONDS");
    assert_eq!(json["value"], 5);
}

// ---------------------------------------------------------------------------
// Expectation array serialization (upsert payload)
// ---------------------------------------------------------------------------

#[test]
fn test_expectation_array_serialization() {
    let expectations = vec![
        Expectation::new(HttpRequest::new().path("/a"))
            .respond(HttpResponse::new().status_code(200)),
        Expectation::new(HttpRequest::new().path("/b"))
            .respond(HttpResponse::new().status_code(201)),
    ];
    let json = serde_json::to_value(&expectations).unwrap();
    let arr = json.as_array().unwrap();
    assert_eq!(arr.len(), 2);
    assert_eq!(arr[0]["httpRequest"]["path"], "/a");
    assert_eq!(arr[0]["httpResponse"]["statusCode"], 200);
    assert_eq!(arr[1]["httpRequest"]["path"], "/b");
    assert_eq!(arr[1]["httpResponse"]["statusCode"], 201);
}

// ---------------------------------------------------------------------------
// RetrieveType / ClearType / RetrieveFormat string values
// ---------------------------------------------------------------------------

#[test]
fn test_retrieve_type_strings() {
    assert_eq!(RetrieveType::Requests.as_str(), "REQUESTS");
    assert_eq!(RetrieveType::ActiveExpectations.as_str(), "ACTIVE_EXPECTATIONS");
    assert_eq!(RetrieveType::RecordedExpectations.as_str(), "RECORDED_EXPECTATIONS");
    assert_eq!(RetrieveType::Logs.as_str(), "LOGS");
    assert_eq!(RetrieveType::RequestResponses.as_str(), "REQUEST_RESPONSES");
}

#[test]
fn test_retrieve_format_strings() {
    assert_eq!(RetrieveFormat::Json.as_str(), "JSON");
    assert_eq!(RetrieveFormat::LogEntries.as_str(), "LOG_ENTRIES");
}

#[test]
fn test_clear_type_strings() {
    assert_eq!(ClearType::All.as_str(), "ALL");
    assert_eq!(ClearType::Log.as_str(), "LOG");
    assert_eq!(ClearType::Expectations.as_str(), "EXPECTATIONS");
}
