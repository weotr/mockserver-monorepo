//! Integration tests that require a running MockServer.
//!
//! These tests are marked `#[ignore]` so they are skipped by default.
//! Run them with:
//!
//! ```sh
//! MOCKSERVER_URL=http://localhost:1080 cargo test -- --ignored
//! ```
//!
//! If `MOCKSERVER_URL` is not set, the tests will panic with a clear message.

use mockserver_client::*;

fn get_client() -> MockServerClient {
    let url = std::env::var("MOCKSERVER_URL")
        .expect("MOCKSERVER_URL must be set (e.g., http://localhost:1080)");

    // Parse host and port from URL
    let url = url.trim_end_matches('/');
    let without_scheme = url
        .strip_prefix("http://")
        .or_else(|| url.strip_prefix("https://"))
        .unwrap_or(url);

    let (host, port_str) = without_scheme
        .rsplit_once(':')
        .expect("MOCKSERVER_URL must include port (e.g., http://localhost:1080)");
    let port: u16 = port_str.parse().expect("Port must be a valid u16");
    let secure = url.starts_with("https://");

    ClientBuilder::new(host, port)
        .secure(secure)
        .build()
        .expect("Failed to build client")
}

#[test]
#[ignore]
fn test_create_expectation_and_verify() {
    let client = get_client();
    client.reset().expect("reset failed");

    // Create an expectation
    client
        .when(HttpRequest::new().method("GET").path("/integration-test"))
        .respond(HttpResponse::new().status_code(200).body("integration OK"))
        .expect("creating expectation failed");

    // Retrieve active expectations
    let expectations = client
        .retrieve_active_expectations(None)
        .expect("retrieve failed");
    assert!(
        !expectations.is_empty(),
        "Should have at least one active expectation"
    );

    // Clean up
    client.reset().expect("reset failed");
}

#[test]
#[ignore]
fn test_status() {
    let client = get_client();
    let ports = client.status().expect("status failed");
    assert!(!ports.ports.is_empty(), "Server should report at least one port");
}

#[test]
#[ignore]
fn test_clear_and_reset() {
    let client = get_client();

    // Create an expectation
    client
        .when(HttpRequest::new().method("POST").path("/to-clear"))
        .respond(HttpResponse::new().status_code(201))
        .expect("creating expectation failed");

    // Clear it
    client
        .clear(
            Some(&HttpRequest::new().path("/to-clear")),
            Some(ClearType::Expectations),
        )
        .expect("clear failed");

    // Reset everything
    client.reset().expect("reset failed");
}

#[test]
#[ignore]
fn test_verify_failure() {
    let client = get_client();
    client.reset().expect("reset failed");

    // Verify something that never happened — should fail
    let result = client.verify(
        HttpRequest::new().method("DELETE").path("/never-called"),
        VerificationTimes::at_least(1),
    );

    assert!(
        result.is_err(),
        "Verification should fail for a request that was never received"
    );
    match result.unwrap_err() {
        Error::VerificationFailure(_) => {} // expected
        other => panic!("Expected VerificationFailure, got: {other}"),
    }
}

#[test]
#[ignore]
fn test_forward_expectation() {
    let client = get_client();
    client.reset().expect("reset failed");

    // Create a forward expectation (won't actually forward, just tests the API)
    client
        .when(HttpRequest::new().method("GET").path("/forward-test"))
        .forward(HttpForward::new("httpbin.org", 80).scheme("HTTP"))
        .expect("creating forward expectation failed");

    let expectations = client
        .retrieve_active_expectations(None)
        .expect("retrieve failed");
    assert!(!expectations.is_empty());

    client.reset().expect("reset failed");
}

#[test]
#[ignore]
fn test_respond_with_advanced_response_builders() {
    // Exercises the `respond_with_*` fluent aliases (cross-client naming
    // parity with the Python/PHP/.NET clients) end-to-end against a live
    // server. Each registers an expectation with one advanced response action.
    let client = get_client();
    client.reset().expect("reset failed");

    client
        .when(HttpRequest::new().method("GET").path("/sse"))
        .respond_with_sse(HttpSseResponse::new().event(SseEvent::new().data("tick")))
        .expect("respond_with_sse failed");

    client
        .when(HttpRequest::new().method("GET").path("/ws"))
        .respond_with_web_socket(
            HttpWebSocketResponse::new().message(WebSocketMessage::text("hi")),
        )
        .expect("respond_with_web_socket failed");

    client
        .when(HttpRequest::new().path("/dns"))
        .respond_with_dns(DnsResponse::new().answer_record(DnsRecord::a("host", "10.0.0.1")))
        .expect("respond_with_dns failed");

    client
        .when(HttpRequest::new().path("/raw"))
        .respond_with_binary(BinaryResponse::from_bytes([0x00, 0xFF]))
        .expect("respond_with_binary failed");

    client
        .when(HttpRequest::new().path("/grpc"))
        .respond_with_grpc_stream(
            GrpcStreamResponse::new().message(GrpcStreamMessage::json("{}")),
        )
        .expect("respond_with_grpc_stream failed");

    let expectations = client
        .retrieve_active_expectations(None)
        .expect("retrieve failed");
    assert_eq!(
        expectations.len(),
        5,
        "all five advanced-response expectations should be registered"
    );

    client.reset().expect("reset failed");
}

#[test]
#[ignore]
fn test_verify_sequence() {
    let client = get_client();
    client.reset().expect("reset failed");

    // Verify a sequence that did not happen — should fail
    let result = client.verify_sequence(vec![
        HttpRequest::new().path("/seq-1"),
        HttpRequest::new().path("/seq-2"),
    ]);

    assert!(result.is_err());
    client.reset().expect("reset failed");
}
