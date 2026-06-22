// Demonstrates creating a basic MockServer expectation, exercising it with a
// real HTTP request, and verifying that the request was received.
//
// Prerequisites: MockServer running on localhost:1080
//   docker run -d -p 1080:1080 mockserver/mockserver

use mockserver_client::{ClientBuilder, HttpRequest, HttpResponse, VerificationTimes};

fn main() -> mockserver_client::Result<()> {
    let client = ClientBuilder::new("localhost", 1080).build()?;

    // -------------------------------------------------------------------
    // 1. Create an expectation: GET /hello -> 200 "Hello from Rust!"
    // -------------------------------------------------------------------
    client
        .when(HttpRequest::new().method("GET").path("/hello"))
        .respond(
            HttpResponse::new()
                .status_code(200)
                .header("Content-Type", "text/plain")
                .body("Hello from Rust!"),
        )?;
    println!("1. Created expectation: GET /hello -> 200 \"Hello from Rust!\"");

    // -------------------------------------------------------------------
    // 2. Send a test request through MockServer
    // -------------------------------------------------------------------
    let http = reqwest::blocking::Client::new();
    let resp = http
        .get("http://localhost:1080/hello")
        .send()
        .expect("test request failed");
    let status = resp.status().as_u16();
    let body = resp.text().unwrap_or_default();
    println!("\n--- Test request: GET /hello ---");
    println!("Status: {status}");
    println!("Body:   {body}");

    // -------------------------------------------------------------------
    // 3. Verify the request was received at least once
    // -------------------------------------------------------------------
    client.verify(
        HttpRequest::new().path("/hello"),
        VerificationTimes::at_least(1),
    )?;
    println!("\n2. Verified: GET /hello received at least once");

    // -------------------------------------------------------------------
    // Clean up
    // -------------------------------------------------------------------
    client.reset()?;
    println!("\nAll expectations cleared.");
    Ok(())
}
