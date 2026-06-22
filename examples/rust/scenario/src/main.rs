// Demonstrates MockServer's stateful-scenario features from the Rust client.
//
// Runs the 5 canonical scenarios in sequence, each against a fresh server
// (reset before each), exercising the data plane with real HTTP requests and
// asserting every outcome. Prints `PASS: <scenario>` per scenario and exits 0
// only if all pass, non-zero otherwise.
//
// Prerequisites: MockServer running, discovered via:
//   MOCKSERVER_HOST (default localhost)
//   MOCKSERVER_PORT (default 1080)
//   docker run -d -p 1080:1080 mockserver/mockserver

use std::time::Duration;

use mockserver_client::{
    ClientBuilder, CrossProtocolScenario, CrossProtocolTrigger, Expectation, HttpRequest,
    HttpResponse, MockServerClient, ResponseMode, Times,
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

    // Each scenario resets the server first so it is self-contained and
    // order-independent. A failed scenario records the error and we exit
    // non-zero after running all of them.
    let scenarios: Vec<(&str, fn(&MockServerClient, &reqwest::blocking::Client, &str) -> Result<(), String>)> = vec![
        ("state_machine", state_machine),
        ("sequential_cycling", sequential_cycling),
        ("timed_transition", timed_transition),
        ("external_trigger", external_trigger),
        ("cross_protocol", cross_protocol),
    ];

    let mut all_passed = true;
    for (name, run) in scenarios {
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

    if all_passed {
        println!("\nAll scenarios passed.");
        std::process::exit(0);
    } else {
        eprintln!("\nOne or more scenarios failed.");
        std::process::exit(1);
    }
}

// ---------------------------------------------------------------------------
// Data-plane helpers
// ---------------------------------------------------------------------------

/// Perform a GET and return (status, body).
fn get(
    http: &reqwest::blocking::Client,
    base_url: &str,
    path: &str,
) -> Result<(u16, String), String> {
    let resp = http
        .get(format!("{base_url}{path}"))
        .send()
        .map_err(|e| format!("GET {path} failed: {e}"))?;
    let status = resp.status().as_u16();
    let body = resp.text().unwrap_or_default();
    Ok((status, body))
}

/// Perform a POST and return (status, body).
fn post(
    http: &reqwest::blocking::Client,
    base_url: &str,
    path: &str,
) -> Result<(u16, String), String> {
    let resp = http
        .post(format!("{base_url}{path}"))
        .send()
        .map_err(|e| format!("POST {path} failed: {e}"))?;
    let status = resp.status().as_u16();
    let body = resp.text().unwrap_or_default();
    Ok((status, body))
}

fn expect_status(label: &str, actual: u16, expected: u16) -> Result<(), String> {
    if actual == expected {
        Ok(())
    } else {
        Err(format!("{label}: expected status {expected}, got {actual}"))
    }
}

fn expect_contains(label: &str, body: &str, needle: &str) -> Result<(), String> {
    if body.contains(needle) {
        Ok(())
    } else {
        Err(format!("{label}: expected body to contain {needle:?}, got {body:?}"))
    }
}

// ---------------------------------------------------------------------------
// 1. state_machine — login flow
// ---------------------------------------------------------------------------

fn state_machine(
    client: &MockServerClient,
    http: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<(), String> {
    // Scenario `LoginFlow`, default start state is `Started`.
    let expectations = vec![
        // POST /login (Started -> LoggedIn), once -> 200 {"token":"abc123"}
        Expectation::new(HttpRequest::new().method("POST").path("/login"))
            .scenario_name("LoginFlow")
            .scenario_state("Started")
            .new_scenario_state("LoggedIn")
            .times(Times::once())
            .respond(HttpResponse::new().status_code(200).body(r#"{"token":"abc123"}"#)),
        // GET /profile when LoggedIn -> 200 {"name":"Alice"}
        Expectation::new(HttpRequest::new().method("GET").path("/profile"))
            .scenario_name("LoginFlow")
            .scenario_state("LoggedIn")
            .respond(HttpResponse::new().status_code(200).body(r#"{"name":"Alice"}"#)),
        // GET /profile when Started -> 401 {"error":"Not authenticated"}
        Expectation::new(HttpRequest::new().method("GET").path("/profile"))
            .scenario_name("LoginFlow")
            .scenario_state("Started")
            .respond(HttpResponse::new().status_code(401).body(r#"{"error":"Not authenticated"}"#)),
    ];
    client.upsert(&expectations).map_err(|e| e.to_string())?;

    // GET /profile -> 401 (not authenticated yet)
    let (status, body) = get(http, base_url, "/profile")?;
    expect_status("GET /profile (before login)", status, 401)?;
    expect_contains("GET /profile (before login)", &body, "Not authenticated")?;

    // POST /login -> 200 with token
    let (status, body) = post(http, base_url, "/login")?;
    expect_status("POST /login", status, 200)?;
    expect_contains("POST /login", &body, "abc123")?;

    // GET /profile -> 200 name=Alice
    let (status, body) = get(http, base_url, "/profile")?;
    expect_status("GET /profile (after login)", status, 200)?;
    expect_contains("GET /profile (after login)", &body, "Alice")?;

    Ok(())
}

// ---------------------------------------------------------------------------
// 2. sequential_cycling — multiple responses, one expectation (no scenario)
// ---------------------------------------------------------------------------

fn sequential_cycling(
    client: &MockServerClient,
    http: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<(), String> {
    // GET /api/status with three responses, SEQUENTIAL (default) cycling.
    let expectation = Expectation::new(HttpRequest::new().method("GET").path("/api/status"))
        .http_responses(vec![
            HttpResponse::new().status_code(200).body(r#"{"status":"ok"}"#),
            HttpResponse::new().status_code(503).body(r#"{"status":"degraded"}"#),
            HttpResponse::new().status_code(200).body(r#"{"status":"ok"}"#),
        ])
        .response_mode(ResponseMode::Sequential);
    client.upsert(&[expectation]).map_err(|e| e.to_string())?;

    // 4 calls return 200, 503, 200, 200 (4th cycles back to the first).
    let expected = [200u16, 503, 200, 200];
    for (i, exp_status) in expected.iter().enumerate() {
        let (status, _body) = get(http, base_url, "/api/status")?;
        expect_status(&format!("GET /api/status call #{}", i + 1), status, *exp_status)?;
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// 3. timed_transition — scenario REST helper, timed auto-transition
// ---------------------------------------------------------------------------

fn timed_transition(
    client: &MockServerClient,
    http: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<(), String> {
    let expectations = vec![
        Expectation::new(HttpRequest::new().method("GET").path("/status"))
            .scenario_name("DeployFlow")
            .scenario_state("Deploying")
            .respond(HttpResponse::new().status_code(200).body(r#"{"status":"deploying"}"#)),
        Expectation::new(HttpRequest::new().method("GET").path("/status"))
            .scenario_name("DeployFlow")
            .scenario_state("Deployed")
            .respond(HttpResponse::new().status_code(200).body(r#"{"status":"complete"}"#)),
    ];
    client.upsert(&expectations).map_err(|e| e.to_string())?;

    // Set Deploying now, auto-transition to Deployed after 1000ms.
    client
        .scenario("DeployFlow")
        .set_timed("Deploying", 1000, "Deployed")
        .map_err(|e| e.to_string())?;

    // Immediately: deploying.
    let (status, body) = get(http, base_url, "/status")?;
    expect_status("GET /status (deploying)", status, 200)?;
    expect_contains("GET /status (deploying)", &body, "deploying")?;

    // Wait past the transition window, then: complete.
    std::thread::sleep(Duration::from_millis(1300));
    let (status, body) = get(http, base_url, "/status")?;
    expect_status("GET /status (complete)", status, 200)?;
    expect_contains("GET /status (complete)", &body, "complete")?;

    Ok(())
}

// ---------------------------------------------------------------------------
// 4. external_trigger — scenario REST helper, external trigger
// ---------------------------------------------------------------------------

fn external_trigger(
    client: &MockServerClient,
    http: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<(), String> {
    let expectations = vec![
        Expectation::new(HttpRequest::new().method("GET").path("/health"))
            .scenario_name("HealthFlow")
            .scenario_state("Started")
            .respond(HttpResponse::new().status_code(200).body(r#"{"status":"healthy"}"#)),
        Expectation::new(HttpRequest::new().method("GET").path("/health"))
            .scenario_name("HealthFlow")
            .scenario_state("Down")
            .respond(HttpResponse::new().status_code(503).body(r#"{"status":"down"}"#)),
    ];
    client.upsert(&expectations).map_err(|e| e.to_string())?;

    // Default start state `Started`: healthy.
    let (status, body) = get(http, base_url, "/health")?;
    expect_status("GET /health (healthy)", status, 200)?;
    expect_contains("GET /health (healthy)", &body, "healthy")?;

    // Externally trigger transition to Down.
    client
        .scenario("HealthFlow")
        .trigger("Down")
        .map_err(|e| e.to_string())?;

    // Now: down.
    let (status, body) = get(http, base_url, "/health")?;
    expect_status("GET /health (down)", status, 503)?;
    expect_contains("GET /health (down)", &body, "down")?;

    Ok(())
}

// ---------------------------------------------------------------------------
// 5. cross_protocol — crossProtocolScenarios (HTTP_REQUEST trigger, runnable)
// ---------------------------------------------------------------------------

fn cross_protocol(
    client: &MockServerClient,
    http: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<(), String> {
    let expectations = vec![
        // GET /events -> 200, and observing it advances ConnFlow to Connected.
        Expectation::new(HttpRequest::new().method("GET").path("/events"))
            .cross_protocol_scenario(
                CrossProtocolScenario::new(CrossProtocolTrigger::HttpRequest, "ConnFlow", "Connected")
                    .match_pattern("/events"),
            )
            .respond(HttpResponse::new().status_code(200).body(r#"{"events":"subscribed"}"#)),
        // GET /api/conn-status only matches once ConnFlow is Connected.
        Expectation::new(HttpRequest::new().method("GET").path("/api/conn-status"))
            .scenario_name("ConnFlow")
            .scenario_state("Connected")
            .respond(HttpResponse::new().status_code(200).body(r#"{"status":"connected"}"#)),
    ];
    client.upsert(&expectations).map_err(|e| e.to_string())?;

    // Before the trigger fires, /api/conn-status is unmatched -> 404.
    let (status, _body) = get(http, base_url, "/api/conn-status")?;
    expect_status("GET /api/conn-status (before)", status, 404)?;

    // Observe /events -> 200, which advances ConnFlow to Connected.
    let (status, _body) = get(http, base_url, "/events")?;
    expect_status("GET /events", status, 200)?;

    // Now /api/conn-status -> 200 connected.
    let (status, body) = get(http, base_url, "/api/conn-status")?;
    expect_status("GET /api/conn-status (after)", status, 200)?;
    expect_contains("GET /api/conn-status (after)", &body, "connected")?;

    Ok(())
}
