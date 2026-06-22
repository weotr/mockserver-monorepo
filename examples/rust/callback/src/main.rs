// Demonstrates MockServer callbacks from the Rust client.
//
// Two flavours of callback:
//
//   1. object_callback — an in-process Rust closure produces the response. The
//      client opens the callback WebSocket, MockServer hands it a clientId, and
//      on each matching request the closure is invoked with the request and
//      returns the response. The response here is DERIVED from the request, so
//      it proves the closure actually ran (a static expectation could not do
//      this).
//
//   2. class_callback — a declarative, REST-only callback that names a
//      server-side class. No WebSocket. We only assert the server ACCEPTS the
//      expectation (200/201 on upsert); the class need not exist on the server
//      to validate the wire shape.
//
// Prints `PASS: <name>` per case and exits 0 only if all pass, non-zero otherwise.
//
// Prerequisites: MockServer running, discovered via:
//   MOCKSERVER_HOST (default localhost)
//   MOCKSERVER_PORT (default 1080)
//   docker run -d -p 1080:1080 mockserver/mockserver

use mockserver_client::{
    ClientBuilder, Expectation, HttpRequest, HttpResponse, MockServerClient,
};

fn main() {
    let host = std::env::var("MOCKSERVER_HOST").unwrap_or_else(|_| "localhost".to_string());
    let port: u16 = std::env::var("MOCKSERVER_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(1080);

    let client = match ClientBuilder::new(host.clone(), port).build() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("FAILED to build client for {host}:{port}: {e}");
            std::process::exit(1);
        }
    };

    let base_url = format!("http://{host}:{port}");
    let http = reqwest::blocking::Client::new();

    let cases: Vec<(&str, fn(&MockServerClient, &reqwest::blocking::Client, &str) -> Result<(), String>)> = vec![
        ("object_callback", object_callback),
        ("class_callback", class_callback),
    ];

    let mut all_passed = true;
    for (name, run) in cases {
        if let Err(e) = client.reset() {
            eprintln!("FAIL: {name} (reset failed: {e})");
            all_passed = false;
            continue;
        }
        match run(&client, &http, &base_url) {
            Ok(()) => println!("PASS: {name}"),
            Err(e) => {
                eprintln!("FAIL: {name} ({e})");
                all_passed = false;
            }
        }
    }

    // Drop the callback WebSocket cleanly before exit.
    client.close_breakpoint_websocket();

    if all_passed {
        println!("\nAll callback examples passed.");
        std::process::exit(0);
    } else {
        eprintln!("\nOne or more callback examples failed.");
        std::process::exit(1);
    }
}

// ---------------------------------------------------------------------------
// 1. object_callback — a Rust closure derives the response from the request
// ---------------------------------------------------------------------------

fn object_callback(
    client: &MockServerClient,
    http: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<(), String> {
    // The closure echoes a request header back into the response body and sets
    // a status code derived from a query parameter, so the response could only
    // have been produced by running the closure on this specific request.
    client
        .mock_with_callback(
            HttpRequest::new().method("GET").path("/greet"),
            |req| {
                let name = req
                    .query_string_parameters
                    .as_ref()
                    .and_then(|q| q.get("name"))
                    .and_then(|v| v.first())
                    .cloned()
                    .unwrap_or_else(|| "stranger".to_string());
                HttpResponse::new()
                    .status_code(200)
                    .header("X-Handled-By", "rust-closure")
                    .body(format!("hello {name}"))
            },
        )
        .map_err(|e| format!("register object callback: {e}"))?;

    // Send a data-plane request and assert the DYNAMIC response.
    let resp = http
        .get(format!("{base_url}/greet?name=Ada"))
        .send()
        .map_err(|e| format!("GET /greet failed: {e}"))?;
    let status = resp.status().as_u16();
    let handled_by = resp
        .headers()
        .get("X-Handled-By")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let body = resp.text().unwrap_or_default();

    if status != 200 {
        return Err(format!("expected status 200, got {status}"));
    }
    if handled_by != "rust-closure" {
        return Err(format!("expected X-Handled-By=rust-closure, got {handled_by:?}"));
    }
    if body != "hello Ada" {
        return Err(format!("expected body \"hello Ada\", got {body:?}"));
    }

    // A second, different request proves the closure runs per-request.
    let resp = http
        .get(format!("{base_url}/greet?name=Grace"))
        .send()
        .map_err(|e| format!("GET /greet (2) failed: {e}"))?;
    let body = resp.text().unwrap_or_default();
    if body != "hello Grace" {
        return Err(format!("expected body \"hello Grace\", got {body:?}"));
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// 2. class_callback — declarative REST-only callback, server accepts the shape
// ---------------------------------------------------------------------------

fn class_callback(
    client: &MockServerClient,
    _http: &reqwest::blocking::Client,
    _base_url: &str,
) -> Result<(), String> {
    // The class need not exist on the server to validate the wire shape — the
    // server stores the expectation. A successful upsert (no error) is the
    // assertion; the server echoes the expectation carrying the class callback.
    let created = client
        .upsert(&[Expectation::new(
            HttpRequest::new().method("GET").path("/class-cb"),
        )
        .respond_with_class_callback("com.example.MyResponseCallback")])
        .map_err(|e| format!("server rejected class-callback expectation: {e}"))?;

    let cb_class = created
        .first()
        .and_then(|e| e.http_response_class_callback.as_ref())
        .map(|cb| cb.callback_class.clone());

    match cb_class.as_deref() {
        Some("com.example.MyResponseCallback") => Ok(()),
        other => Err(format!(
            "server did not echo the class callback (got {other:?})"
        )),
    }
}
