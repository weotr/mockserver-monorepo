# mockserver-client

An idiomatic Rust client for [MockServer](https://www.mock-server.com)'s control-plane REST API.

## Installation

Add to your `Cargo.toml`:

```toml
[dev-dependencies]
mockserver-client = "7.0"
```

## Quick Start

```rust
use mockserver_client::{ClientBuilder, HttpRequest, HttpResponse, VerificationTimes};

fn main() -> mockserver_client::Result<()> {
    let client = ClientBuilder::new("localhost", 1080).build()?;

    // Create an expectation
    client.when(HttpRequest::new().method("GET").path("/hello"))
        .respond(HttpResponse::new().status_code(200).body("world"))?;

    // Verify the request was received
    client.verify(
        HttpRequest::new().path("/hello"),
        VerificationTimes::at_least(1),
    )?;

    // Reset all expectations
    client.reset()?;
    Ok(())
}
```

## Features

- **Fluent builder API** — `client.when(request).respond(response)`
- **Response, Forward, and Error actions** — full MVP control-plane coverage
- **Verification** — `verify` (count-based) and `verify_sequence` (order-based)
- **Retrieve** — recorded requests, active expectations, recorded expectations, logs
- **Clear / Reset** — by request matcher, by expectation ID, or full reset
- **Status / Bind** — query ports, bind additional ports
- **Blocking (synchronous)** — uses `reqwest` blocking client; no async runtime needed
- **TLS support** — optional HTTPS with configurable certificate verification

## API Overview

```rust
use mockserver_client::*;

let client = ClientBuilder::new("localhost", 1080).build().unwrap();

// Fluent expectation creation
client.when(HttpRequest::new().method("POST").path("/api/users"))
    .times(Times::exactly(3))
    .respond(HttpResponse::new()
        .status_code(201)
        .header("Location", "/api/users/1")
        .body(r#"{"id": 1}"#))?;

// Forward action
client.when(HttpRequest::new().path("/proxy"))
    .forward(HttpForward::new("backend.local", 8080).scheme("HTTP"))?;

// Verify
client.verify(
    HttpRequest::new().method("POST").path("/api/users"),
    VerificationTimes::between(1, 3),
)?;

// Verify sequence
client.verify_sequence(vec![
    HttpRequest::new().path("/first"),
    HttpRequest::new().path("/second"),
])?;

// Clear by request matcher
client.clear(
    Some(&HttpRequest::new().path("/api/users")),
    Some(ClearType::Expectations),
)?;

// Clear by ID
client.clear_by_id("my-expectation-id", None)?;

// Retrieve recorded requests
let requests = client.retrieve_recorded_requests(None)?;

// Retrieve active expectations
let expectations = client.retrieve_active_expectations(None)?;

// Server status
let ports = client.status()?;
println!("Listening on: {:?}", ports.ports);

// Reset everything
client.reset()?;
```

## Building

```sh
cargo build
cargo test
cargo clippy
```

## Integration Tests

Integration tests require a running MockServer and are skipped by default:

```sh
# Start MockServer (e.g., via Docker)
docker run -d -p 1080:1080 mockserver/mockserver

# Run integration tests
MOCKSERVER_URL=http://localhost:1080 cargo test -- --ignored
```

## License

Apache-2.0
