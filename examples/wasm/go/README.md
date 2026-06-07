# MockServer WASM Body-Matcher Rule -- Go / TinyGo Example

## What it demonstrates

This example implements a custom MockServer body-matcher rule as a freestanding
WebAssembly module, written in Go and compiled with TinyGo. The rule matches
HTTP request bodies containing a JSON-style `"amount": <number>` where the
value is greater than 1000 -- flagging "large" payment requests.

This is the same rule as the sibling [Rust example](../rust/), so the two are
directly comparable.

The compiled module exports:

| Export   | Signature                        | Description                        |
|----------|----------------------------------|------------------------------------|
| `match`  | `(i32 ptr, i32 len) -> i32`     | Returns 1 if matched, 0 otherwise  |
| `memory` | linear memory (min 1 page)       | MockServer writes the body here    |

## Prerequisites

**TinyGo** is required to compile this example. Standard Go's WebAssembly
targets (`GOOS=js GOARCH=wasm` and `GOOS=wasip1`) will not work because they
require a JavaScript runtime or WASI host respectively. MockServer needs a
completely freestanding module with no imports.

Install TinyGo (v0.34.0 or later recommended):

- macOS: `brew install tinygo`
- Linux / other: see <https://tinygo.org/getting-started/install/>

Verify the installation:

```bash
tinygo version
```

### Why `wasm-unknown` and not WASI?

MockServer's WASM runtime (chicory) instantiates modules with **no host
imports**. The module must:

1. Export a `match(i32, i32) -> i32` function
2. Export its linear `memory`
3. Import nothing -- no WASI, no `env`, no JS glue

TinyGo's `wasm-unknown` target produces exactly this kind of freestanding
module. However, by default it passes `--import-memory` to the linker, which
causes memory to be *imported* rather than *exported*. The included
`mockserver-wasm.json` custom target file inherits from `wasm-unknown` but
overrides the linker flags to use `--export-memory` instead, which is what
MockServer expects.

## Build

```bash
cd examples/wasm/go
tinygo build -target=./mockserver-wasm.json -no-debug -o match.wasm .
```

Flags explained:

| Flag | Purpose |
|------|---------|
| `-target=./mockserver-wasm.json` | Use the custom target that inherits `wasm-unknown` but exports memory |
| `-no-debug` | Strip DWARF debug info to minimise module size |
| `-o match.wasm` | Output file name |

The build produces `match.wasm` (typically 1--3 KiB). You can inspect it with:

```bash
wasm-objdump -x match.wasm    # if wabt is installed
# or
wasm-tools print match.wasm   # if wasm-tools is installed
```

Verify the module exports `match` and `memory`, and has no imports.

> **Note:** The compiled `match.wasm` is not committed to the repository
> because TinyGo was not available in the environment where this example was
> authored. The Rust sibling example ships a prebuilt `.wasm` because `rustc`
> was available. Run the build command above to produce the binary.

## Upload to MockServer

Start MockServer with WASM support enabled:

```bash
java -Dmockserver.wasmEnabled=true -jar mockserver-netty-shaded.jar -serverPort 1080
```

Upload the compiled module:

```bash
curl -X PUT "http://localhost:1080/mockserver/wasm/modules?name=largeAmount" \
     --data-binary @match.wasm
```

Create an expectation that uses the WASM rule:

```bash
curl -X PUT "http://localhost:1080/mockserver/expectation" \
     -H "Content-Type: application/json" \
     -d '{
       "httpRequest": {
         "method": "POST",
         "path": "/payments",
         "body": {
           "type": "WASM",
           "moduleName": "largeAmount"
         }
       },
       "httpResponse": {
         "statusCode": 200,
         "body": "large payment accepted"
       }
     }'
```

## Expected output

Requests whose body contains `"amount"` with a value greater than 1000 will
match; all others will not.

```bash
# Matches (amount 5000 > 1000):
curl -X POST http://localhost:1080/payments \
     -d '{"currency": "USD", "amount": 5000}'
# => 200, "large payment accepted"

# Does NOT match (amount 50 <= 1000):
curl -X POST http://localhost:1080/payments \
     -d '{"currency": "USD", "amount": 50}'
# => 404 (no matching expectation)

# Does NOT match (no amount field):
curl -X POST http://localhost:1080/payments \
     -d '{"currency": "USD"}'
# => 404 (no matching expectation)
```

## Files

| File | Purpose |
|------|---------|
| `main.go` | The WASM rule implementation |
| `go.mod` | Go module definition |
| `mockserver-wasm.json` | Custom TinyGo target (inherits `wasm-unknown`, exports memory) |
| `README.md` | This file |

## How it works

1. MockServer writes the HTTP request body into the module's linear memory at
   offset 0 as UTF-8 bytes.
2. It calls `match(0, bodyLength)`.
3. The Go code constructs a `[]byte` slice from the raw pointer and length
   using `unsafe.Slice`, then scans for the byte pattern `"amount"` followed
   by `:` and ASCII digits.
4. If the parsed number exceeds 1000, it returns 1 (match); otherwise 0.

The implementation is deliberately dependency-free -- no `encoding/json`,
no `regexp`, no `fmt` -- to keep the compiled WASM module as small as possible
and avoid pulling in standard library code that might require host imports.
