//! Unit tests for callback models and object-callback message routing.
//! These run with NO server — they test serialization of class/object callbacks
//! on the Expectation model and the object-callback reply-envelope routing.

use mockserver_client::*;
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// HttpClassCallback serialization
// ---------------------------------------------------------------------------

#[test]
fn test_class_callback_serializes_callback_class() {
    let cb = HttpClassCallback::new("com.example.MyCallback");
    let json = serde_json::to_value(&cb).unwrap();
    assert_eq!(json["callbackClass"], "com.example.MyCallback");
    // Optional fields omitted when unset.
    assert!(json.get("delay").is_none());
    assert!(json.get("primary").is_none());
}

#[test]
fn test_class_callback_serializes_delay_and_primary() {
    let cb = HttpClassCallback::new("com.example.MyCallback")
        .delay(Delay::milliseconds(250))
        .primary(true);
    let json = serde_json::to_value(&cb).unwrap();
    assert_eq!(json["callbackClass"], "com.example.MyCallback");
    assert_eq!(json["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["delay"]["value"], 250);
    assert_eq!(json["primary"], true);
}

#[test]
fn test_expectation_response_class_callback_field_name() {
    let exp = Expectation::new(HttpRequest::new().method("GET").path("/cb"))
        .respond_with_class_callback("com.example.ResponseCallback");
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(
        json["httpResponseClassCallback"]["callbackClass"],
        "com.example.ResponseCallback"
    );
    // Must use the camelCase wire field name, not the snake_case Rust field.
    assert!(json.get("http_response_class_callback").is_none());
}

#[test]
fn test_expectation_forward_class_callback_field_name() {
    let exp = Expectation::new(HttpRequest::new().method("GET").path("/fwd"))
        .forward_with_class_callback("com.example.ForwardCallback");
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(
        json["httpForwardClassCallback"]["callbackClass"],
        "com.example.ForwardCallback"
    );
}

#[test]
fn test_class_callback_builder_from_value() {
    let exp = Expectation::new(HttpRequest::new().path("/x")).respond_class_callback(
        HttpClassCallback::new("com.example.C").primary(false),
    );
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["httpResponseClassCallback"]["callbackClass"], "com.example.C");
    assert_eq!(json["httpResponseClassCallback"]["primary"], false);
}

// ---------------------------------------------------------------------------
// HttpObjectCallback serialization
// ---------------------------------------------------------------------------

#[test]
fn test_object_callback_serializes_client_id() {
    let cb = HttpObjectCallback::new("client-abc");
    let json = serde_json::to_value(&cb).unwrap();
    assert_eq!(json["clientId"], "client-abc");
    assert!(json.get("delay").is_none());
}

#[test]
fn test_expectation_response_object_callback_field_name() {
    let exp = Expectation::new(HttpRequest::new().method("GET").path("/obj"))
        .respond_object_callback(HttpObjectCallback::new("client-xyz"));
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["httpResponseObjectCallback"]["clientId"], "client-xyz");
    assert!(json.get("http_response_object_callback").is_none());
}

#[test]
fn test_expectation_forward_object_callback_field_name() {
    let exp = Expectation::new(HttpRequest::new().path("/obj-fwd"))
        .forward_object_callback(HttpObjectCallback::new("fwd-client"));
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["httpForwardObjectCallback"]["clientId"], "fwd-client");
}

// ---------------------------------------------------------------------------
// Object-callback routing (no live WebSocket needed)
// ---------------------------------------------------------------------------

fn make_object_request_json(correlation_id: &str) -> String {
    serde_json::json!({
        "method": "GET",
        "path": "/echo",
        "headers": {
            "WebSocketCorrelationId": [correlation_id]
        }
    })
    .to_string()
}

fn make_breakpoint_request_json(breakpoint_id: &str, correlation_id: &str) -> String {
    serde_json::json!({
        "method": "GET",
        "path": "/echo",
        "headers": {
            "WebSocketCorrelationId": [correlation_id],
            "X-MockServer-BreakpointId": [breakpoint_id]
        }
    })
    .to_string()
}

#[test]
fn test_route_object_callback_produces_response_envelope() {
    let handler: Arc<Mutex<Option<ObjectResponseHandler>>> = Arc::new(Mutex::new(Some(Box::new(
        |req: serde_json::Value| {
            let path = req["path"].as_str().unwrap_or("");
            serde_json::json!({
                "statusCode": 200,
                "body": format!("echo {path}")
            })
        },
    ))));

    let json = make_object_request_json("corr-obj-1");
    let result = route_object_callback(&json, &handler);
    assert!(result.is_some());
    let (type_name, value) = result.unwrap();

    // Reply must be an HttpResponse envelope type.
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
    // The closure-derived response is returned ...
    assert_eq!(value["statusCode"], 200);
    assert_eq!(value["body"], "echo /echo");
    // ... and the WebSocketCorrelationId is echoed back so the server can route it.
    assert_eq!(
        extract_header(&value, "WebSocketCorrelationId"),
        Some("corr-obj-1".to_string())
    );
}

#[test]
fn test_route_object_callback_ignores_breakpoint_frames() {
    // Even with a handler registered, a frame carrying a breakpoint id must NOT
    // be object-routed — it belongs to the breakpoint path.
    let handler: Arc<Mutex<Option<ObjectResponseHandler>>> = Arc::new(Mutex::new(Some(Box::new(
        |_req: serde_json::Value| serde_json::json!({ "statusCode": 200 }),
    ))));

    let json = make_breakpoint_request_json("bp-1", "corr-bp");
    assert!(route_object_callback(&json, &handler).is_none());
}

#[test]
fn test_route_object_callback_none_when_no_handler() {
    let handler: Arc<Mutex<Option<ObjectResponseHandler>>> = Arc::new(Mutex::new(None));
    let json = make_object_request_json("corr-none");
    assert!(route_object_callback(&json, &handler).is_none());
}

#[test]
fn test_route_object_callback_panic_auto_continues() {
    let handler: Arc<Mutex<Option<ObjectResponseHandler>>> = Arc::new(Mutex::new(Some(Box::new(
        |_req: serde_json::Value| panic!("boom"),
    ))));

    let json = make_object_request_json("corr-panic");
    let result = route_object_callback(&json, &handler);
    // On panic we still produce a (passthrough) response envelope with the
    // correlation id echoed, so the exchange is not left hanging.
    assert!(result.is_some());
    let (type_name, value) = result.unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
    assert_eq!(
        extract_header(&value, "WebSocketCorrelationId"),
        Some("corr-panic".to_string())
    );
}
