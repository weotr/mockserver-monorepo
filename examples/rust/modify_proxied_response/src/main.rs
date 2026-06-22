// Demonstrates registering a RESPONSE-phase breakpoint that modifies a proxied
// response in-flight. This is MockServer's interactive breakpoint feature.
//
// How it works:
//   1. Create an "upstream" mock returning a canned JSON response.
//   2. Create a loopback forward (httpOverrideForwardedRequest + socketAddress)
//      so requests to /proxy go back to the SAME MockServer.
//   3. Register a RESPONSE-phase breakpoint whose handler modifies the body
//      and adds a custom header.
//   4. Send a request to /proxy and print the modified response.
//
// Prerequisites:
//   - MockServer running on localhost:1080 WITH breakpoint support
//   - cargo run

use mockserver_client::{ClientBuilder, HttpRequest, HttpResponse};
use serde_json::json;

fn main() -> mockserver_client::Result<()> {
    let client = ClientBuilder::new("localhost", 1080).build()?;
    let http = reqwest::blocking::Client::new();

    // -------------------------------------------------------------------
    // 1. Upstream mock: GET /upstream -> 200 JSON
    // -------------------------------------------------------------------
    client
        .when(HttpRequest::new().method("GET").path("/upstream"))
        .respond(
            HttpResponse::new()
                .status_code(200)
                .header("Content-Type", "application/json")
                .body(r#"{"source":"upstream","modified":false}"#),
        )?;
    println!("1. Created upstream expectation: GET /upstream -> 200");

    // -------------------------------------------------------------------
    // 2. Loopback forward via raw REST API (httpOverrideForwardedRequest)
    // -------------------------------------------------------------------
    let forward_payload = json!([{
        "httpRequest": { "method": "GET", "path": "/proxy" },
        "httpOverrideForwardedRequest": {
            "httpRequest": {
                "path": "/upstream",
                "socketAddress": {
                    "host": "localhost",
                    "port": 1080,
                    "scheme": "HTTP"
                }
            }
        }
    }]);
    let resp = http
        .put("http://localhost:1080/mockserver/expectation")
        .header("Content-Type", "application/json")
        .body(forward_payload.to_string())
        .send()
        .expect("forward expectation request failed");
    assert!(
        resp.status().is_success(),
        "forward expectation failed: {}",
        resp.text().unwrap_or_default()
    );
    println!("2. Created loopback forward: GET /proxy -> forward to /upstream (localhost:1080)");

    // -------------------------------------------------------------------
    // 3. Register a RESPONSE-phase breakpoint on /proxy
    // -------------------------------------------------------------------
    let bp_id = client.add_request_response_breakpoint(
        HttpRequest::new().method("GET").path("/proxy"),
        // REQUEST handler: pass through unchanged
        Box::new(|req| Some(req)),
        // RESPONSE handler: modify the body and add a header
        Box::new(|_req, mut resp| {
            println!("\n   [breakpoint] RESPONSE phase fired!");
            println!("   [breakpoint] Original response body: {}", resp.get("body").unwrap_or(&json!(null)));

            // Replace the body
            resp["body"] = json!(r#"{"source":"upstream","modified":true,"breakpoint":"rust-client"}"#);

            // Add a custom header
            let headers = resp
                .as_object_mut()
                .unwrap()
                .entry("headers")
                .or_insert_with(|| json!({}));
            if let Some(h) = headers.as_object_mut() {
                h.insert(
                    "X-Modified-By".to_string(),
                    json!(["rust-breakpoint-example"]),
                );
            }

            println!("   [breakpoint] Modified response body: {}", resp.get("body").unwrap_or(&json!(null)));
            Some(resp)
        }),
    )?;
    println!("3. Registered RESPONSE breakpoint (id={bp_id}) on GET /proxy");

    // -------------------------------------------------------------------
    // 4. Send request to /proxy -- the breakpoint handler will fire
    // -------------------------------------------------------------------
    println!("\n4. Sending GET /proxy ...");

    // Give the WebSocket a moment to be fully ready
    std::thread::sleep(std::time::Duration::from_millis(200));

    let proxy_resp = http
        .get("http://localhost:1080/proxy")
        .send()
        .expect("proxy request failed");
    let status = proxy_resp.status().as_u16();
    let modified_by = proxy_resp
        .headers()
        .get("X-Modified-By")
        .map(|v| v.to_str().unwrap_or("(invalid)").to_string())
        .unwrap_or_else(|| "(not set)".to_string());
    let body = proxy_resp.text().unwrap_or_default();

    println!("\n--- Response from GET /proxy ---");
    println!("Status:          {status}");
    println!("Body:            {body}");
    println!("X-Modified-By:   {modified_by}");

    // -------------------------------------------------------------------
    // Clean up
    // -------------------------------------------------------------------
    client.remove_breakpoint_matcher(&bp_id)?;
    client.close_breakpoint_websocket();
    client.reset()?;
    println!("\nAll expectations and breakpoints cleared.");
    Ok(())
}
