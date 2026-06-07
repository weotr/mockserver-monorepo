# WASM Rust Example

A MockServer WASM body-matcher rule written in Rust.

## What it demonstrates

Real request-body inspection from a freestanding WASM module: parse the body and
match when it contains a JSON-style `"amount": <number>` greater than `1000`
(e.g. flag "large" payment requests). No `serde`, no `std` — a tiny, dependency-free
module that honours [the MockServer WASM ABI](../README.md#the-mockserver-wasm-abi).

## Prerequisites

- Rust toolchain (`rustup`) with the WASM target:
  ```bash
  rustup target add wasm32-unknown-unknown
  ```

## Build

```bash
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/mockserver_wasm_example.wasm match.wasm
```

A prebuilt **`match.wasm`** is already committed here so you can use it without a Rust
toolchain.

## Run

```bash
# Upload (WASM must be enabled: -Dmockserver.wasmEnabled=true)
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/modules" \
  -H "Content-Type: application/octet-stream" \
  -H "X-WASM-Module-Name: amount-over-1000" \
  --data-binary @match.wasm
```

Then create an expectation whose body matcher is of type WASM referencing module
`amount-over-1000` (see [`docs/code/wasm-rules.md`](../../../docs/code/wasm-rules.md)).

## Expected output

- A request body like `{"amount": 5000}` **matches** (rule returns non-zero).
- A request body like `{"amount": 10}` or with no `amount` field **does not match**.
