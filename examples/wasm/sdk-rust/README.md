# mockserver-wasm-sdk

A minimal, dependency-free Rust authoring SDK for **MockServer WASM matcher rules** that
use the richer ABI (method, path, headers — not just the body).

## What it gives you

MockServer's richer ABI calls an exported `match_request(ptr, len)` with a JSON request
envelope written into linear memory at offset 0:

```json
{ "method": "POST", "path": "/orders", "headers": { "X-Tenant": ["acme"] }, "body": "..." }
```

Rather than hand-parse that in every rule, this SDK gives you typed accessors and a macro
that wires up the ABI export:

```rust
#![no_std]
use mockserver_wasm_sdk::{export_match_request, Request};

fn rule(req: &Request) -> bool {
    req.method() == "POST"
        && req.path() == "/orders"
        && req.header("X-Tenant") == Some("acme")
}

export_match_request!(rule);
```

`Request` exposes `method()`, `path()`, `header(name)` (case-insensitive), and `body()`.
The crate is `no_std`, allocation-free, and pulls in **no dependencies** (no `serde`), so
a rule built on it stays tiny and freestanding on `wasm32-unknown-unknown`.

## Build a rule that uses it

See [`../rust-request/`](../rust-request/) for a complete sample crate. To build:

```bash
rustup target add wasm32-unknown-unknown
cargo build --target wasm32-unknown-unknown --release
```

## Run the SDK's own tests

```bash
cargo test
```

Back-compat: if your rule only needs the body, the legacy body-only `match(ptr, len)`
ABI still works without this SDK — see [`../rust/`](../rust/).
