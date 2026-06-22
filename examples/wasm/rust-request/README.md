# WASM Rust Example — richer ABI (method/path/headers)

A MockServer WASM matcher rule that uses the **richer `match_request` ABI** via
[`mockserver-wasm-sdk`](../sdk-rust/), so it can inspect more than just the body.

## What it demonstrates

It matches when **all** of:

* the method is `POST`,
* the path is exactly `/orders`,
* the `X-Tenant` header equals `acme`.

Because it exports `match_request`, MockServer passes the full JSON request envelope.
Body-only modules that export `match` continue to work unchanged.

## Prerequisites

- Rust toolchain (`rustup`) with the WASM target:
  ```bash
  rustup target add wasm32-unknown-unknown
  ```

## Build

```bash
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/mockserver_wasm_request_example.wasm match-request.wasm
```

A prebuilt **`match-request.wasm`** is already committed here so you can use it without a
Rust toolchain. (MockServer's `mockserver-core` test suite also uses it as an ABI-guard
fixture.)

## Try it

Upload it (WASM must be enabled: `-Dmockserver.wasmEnabled=true`), then test it against a
sample request without creating an expectation:

```bash
curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/test" \
  -H "Content-Type: application/json" \
  -d "{\"module\":\"$(base64 < match-request.wasm | tr -d '\n')\",
       \"request\":{\"method\":\"POST\",\"path\":\"/orders\",\"headers\":{\"X-Tenant\":[\"acme\"]},\"body\":\"{}\"}}"
# Returns: {"matched":true}
```

See [`docs/code/wasm-rules.md`](../../../docs/code/wasm-rules.md) for the full runtime design.
