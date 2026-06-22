# WASM Custom-Rule Examples

MockServer's WASM rule engine lets you write custom request-body **match** logic in
any language that compiles to WebAssembly, upload it at runtime, and use it as a body
matcher. These examples implement the **same rule in two languages** so you can compare:

> Match when the request body contains a JSON-style `"amount": <number>` whose value is **greater than 1000**.

| Example | Language | Toolchain | Prebuilt `.wasm` shipped? |
|---------|----------|-----------|---------------------------|
| [`rust/`](rust/) | Rust | `cargo` + `wasm32-unknown-unknown` target | ✅ yes (`rust/match.wasm`) |
| [`go/`](go/) | Go | TinyGo (`wasm-unknown` target) | ❌ build it yourself (TinyGo wasn't available when these were authored) |

Two more crates demonstrate the **richer ABI** (method/path/headers, not just the body):

| Crate | Purpose |
|-------|---------|
| [`sdk-rust/`](sdk-rust/) | `mockserver-wasm-sdk` — a tiny, dependency-free authoring SDK with typed accessors over the request envelope and an `export_match_request!` macro |
| [`rust-request/`](rust-request/) | A sample rule built on the SDK that matches on method + path + header (ships a prebuilt `match-request.wasm`) |

## The MockServer WASM ABI

Any module you write must satisfy this contract (enforced by
`org.mockserver.wasm.WasmRuntime`). There are **two export shapes**; MockServer prefers
the richer `match_request` when present and falls back to body-only `match` otherwise.

### Body-only — `match`

1. **Export a function** `match(ptr: i32, len: i32) -> i32`.
2. MockServer **writes the request body into your exported linear memory at offset `0`**,
   then calls `match(0, len)` where `len` is the body length in bytes.
3. Return **non-zero to match**, `0` for no match.
4. **Export a linear `memory`.**
5. **No host imports / no WASI** — the module must be *freestanding*. (That is why the
   Rust example targets `wasm32-unknown-unknown` and the Go example uses TinyGo's
   `wasm-unknown` target — *not* WASI and *not* stdlib Go's `js/wasm`.)

### Richer request envelope — `match_request`

Export `match_request(ptr: i32, len: i32) -> i32` instead (same memory/return-value
rules). MockServer writes a UTF-8 **JSON envelope** at offset `0`:

```json
{ "method": "POST", "path": "/orders", "headers": { "X-Tenant": ["acme"] }, "body": "..." }
```

so the module can read the method, path and headers in addition to the body. The
[`sdk-rust/`](sdk-rust/) crate parses this for you.

## Using a module

```bash
# 1. Enable WASM (off by default) — e.g. start MockServer with:
#    -Dmockserver.wasmEnabled=true
# 2. Upload the module (name it, send the raw .wasm bytes):
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/modules" \
  -H "Content-Type: application/octet-stream" \
  -H "X-WASM-Module-Name: amount-over-1000" \
  --data-binary @rust/match.wasm

# 3. Reference it from an expectation's body matcher (type WASM, by module name).
#    See ../../docs/code/wasm-rules.md and the consumer docs (wasm_rules.html).

# List / delete:
curl "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/modules"
curl -X DELETE "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/modules/amount-over-1000"
```

> Configuration: `wasmEnabled` (default `false`), `wasmMaxMemoryPages` (default `256` = 16 MiB).

See [`docs/code/wasm-rules.md`](../../docs/code/wasm-rules.md) for the full runtime design.
