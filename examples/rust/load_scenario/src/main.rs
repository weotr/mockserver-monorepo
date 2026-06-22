// Demonstrates MockServer's Load Scenario registry from the Rust client.
//
// A "load scenario" is a named, server-side traffic generator: you register it
// once (its profile of ramp/hold/pause stages and the request steps it drives),
// then start/stop it by name. While running it generates synthetic traffic
// against the data plane and reports live throughput/latency status. This is the
// registry workflow exercised with the typed Rust client:
//
//   client.load_scenario(&scenario)         register/upsert (PUT /mockserver/loadScenario)
//   client.start_load_scenarios(&names)     start one/many (PUT .../start)
//   client.load_scenarios()                 list all (GET /mockserver/loadScenario)
//   client.get_load_scenario(name)          one scenario + live status (GET .../{name})
//   client.stop_load_scenarios(&names)      stop one/many; &[] = stop all (PUT .../stop)
//   client.run_load_scenario(&scenario)     register + start in one call
//   client.delete_load_scenario(name)       delete one (DELETE .../{name})
//   client.clear_load_scenarios()           clear the registry (DELETE /mockserver/loadScenario)
//
// IMPORTANT: the server must be started with load generation enabled, otherwise
// starting returns HTTP 403:
//   java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-...-jar-with-dependencies.jar -serverPort 1080
//   (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always allowed.
//
// Prints `PASS` and exits 0 on success; exits non-zero on the first failure.
//
// MockServer location is read from MOCKSERVER_HOST (default localhost) and
// MOCKSERVER_PORT (default 1080).

use std::thread::sleep;
use std::time::Duration;

use mockserver_client::{
    ClientBuilder, Delay, Expectation, HttpRequest, HttpResponse, LoadProfile, LoadScenario,
    LoadStage, LoadStep, RampCurve,
};

// A realistic multi-stage scenario built from the typed model: a linear RATE
// ramp (5 -> 50 req/s, capped at 50 VUs), then a 25-VU hold, then a PAUSE. Two
// Velocity-templated steps drive each iteration ($!iteration.index varies the
// request). start_delay_millis defers load for half a second after start. Stage
// VUs are kept within the default safety cap of 50 (loadGenerationMaxVirtualUsers).
fn build_scenario() -> LoadScenario {
    let profile = LoadProfile::of(vec![
        LoadStage::rate_ramp(5.0, 50.0, 30_000, RampCurve::Linear).max_vus(50),
        LoadStage::vu_hold(25, 60_000),
        LoadStage::pause(10_000),
    ]);
    let steps = vec![
        LoadStep::new(HttpRequest::new().method("GET").path("/products/$!iteration.index"))
            .think_time(Delay::milliseconds(500)),
        LoadStep::new(
            HttpRequest::new()
                .method("POST")
                .path("/cart/checkout")
                .body(r#"{"item":"$!iteration.index","qty":1}"#),
        ),
    ];
    LoadScenario::new("checkout-load", profile, steps)
        .template_type("VELOCITY")
        .max_requests(100_000)
        .start_delay_millis(500)
}

fn run() -> Result<(), String> {
    let host = std::env::var("MOCKSERVER_HOST").unwrap_or_else(|_| "localhost".to_string());
    let port: u16 = std::env::var("MOCKSERVER_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(1080);

    let client = ClientBuilder::new(host, port)
        .build()
        .map_err(|e| format!("build client: {e}"))?;

    // A catch-all target expectation so generated traffic gets a 200 to measure.
    client
        .upsert(&[
            Expectation::new(HttpRequest::new().path("/.*"))
                .respond(HttpResponse::new().status_code(200).body("ok")),
        ])
        .map_err(|e| format!("register target expectation: {e}"))?;

    let scenario = build_scenario();

    // 1. Register (does NOT start it yet).
    client
        .load_scenario(&scenario)
        .map_err(|e| format!("register load scenario: {e}"))?;
    println!("registered \"checkout-load\"");

    // 2. Start it (start_load_scenarios takes a slice of names).
    client
        .start_load_scenarios(&["checkout-load"])
        .map_err(|e| format!("start load scenario (is loadGenerationEnabled=true?): {e}"))?;
    println!("started \"checkout-load\"");
    sleep(Duration::from_millis(1500));

    // 3. List all registered scenarios -> {"scenarios":[ <status node>, ... ]}.
    let listing = client
        .load_scenarios()
        .map_err(|e| format!("list load scenarios: {e}"))?;
    let scenarios = listing
        .get("scenarios")
        .and_then(|s| s.as_array())
        .cloned()
        .unwrap_or_default();
    let running = scenarios.iter().any(|s| {
        s.get("name").and_then(|n| n.as_str()) == Some("checkout-load")
            && s.get("state").and_then(|st| st.as_str()) == Some("RUNNING")
    });
    if !running {
        return Err("checkout-load is not RUNNING in the list (is loadGenerationEnabled=true?)".into());
    }
    let listed: Vec<String> = scenarios
        .iter()
        .map(|s| {
            format!(
                "{}={}",
                s.get("name").and_then(|n| n.as_str()).unwrap_or(""),
                s.get("state").and_then(|st| st.as_str()).unwrap_or("")
            )
        })
        .collect();
    println!("listed: {}", listed.join(", "));

    // One scenario's live status (throughput/latency, current stage, ...).
    let status = client
        .get_load_scenario("checkout-load")
        .map_err(|e| format!("get load scenario: {e}"))?;
    println!(
        "status: state={} stageType={} currentTarget={} requestsSent={}",
        status.get("state").and_then(|v| v.as_str()).unwrap_or(""),
        status.get("stageType").and_then(|v| v.as_str()).unwrap_or(""),
        status.get("currentTarget").map(|v| v.to_string()).unwrap_or_default(),
        status.get("requestsSent").map(|v| v.to_string()).unwrap_or_default(),
    );

    // 4. Stop it (pass &[] to stop ALL running scenarios).
    client
        .stop_load_scenarios(&["checkout-load"])
        .map_err(|e| format!("stop load scenario: {e}"))?;
    println!("stopped \"checkout-load\"");

    // Tidy up the registry.
    client
        .clear_load_scenarios()
        .map_err(|e| format!("clear load scenarios: {e}"))?;

    Ok(())
}

fn main() {
    match run() {
        Ok(()) => {
            println!("PASS");
            std::process::exit(0);
        }
        Err(e) => {
            eprintln!("FAIL: {e}");
            std::process::exit(1);
        }
    }
}
