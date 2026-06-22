# Callbacks

## What it demonstrates

How to use the MockServer Go client's two callback mechanisms. The program runs two
checks in sequence, resetting MockServer before each, and asserts the outcome of
every one:

| Check | Demonstrates |
|-------|--------------|
| `object_callback` | A Go closure produces the response at request time. `MockWithCallback` opens a single callback WebSocket, registers the closure, and creates an expectation carrying `httpResponseObjectCallback.clientId`. On a match the server sends the request over the WebSocket and the closure returns a response **derived from the request** — body `hello <path>` plus an `X-Echo` response header echoing the request's `X-Caller` header. `GET /dynamic` with `X-Caller: go-example` returns `200`, body `hello /dynamic`, and `X-Echo: go-example`. |
| `class_callback` | A declarative, REST-only callback that references a server-side class by name. `RespondWithClassCallback("org.mockserver.examples.MyResponseCallback")` upserts an expectation carrying `httpResponseClassCallback.callbackClass`. The class need not exist for the wire shape to validate — the check asserts the server **accepts** the expectation and that it round-trips when the active expectations are re-read. |

## Callback kinds

- **Class callbacks** are pure JSON (no WebSocket) and reference a class on the
  MockServer classpath. Use `RespondWithClassCallback` / `ForwardWithClassCallback`
  (or `...Action` variants to set a delay / primary flag).
- **Object/closure callbacks** are driven over the callback WebSocket. The Go
  client reuses the single breakpoint WebSocket so only one socket is opened per
  client. Use `MockWithCallback` (response) or `MockWithForwardCallback` (forward).

## Prerequisites

- Go 1.21+
- MockServer running (defaults to `localhost:1080`; e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)

## Run

The server location is read from the environment, defaulting to `localhost:1080`:

- `MOCKSERVER_HOST` (default `localhost`)
- `MOCKSERVER_PORT` (default `1080`)

```bash
go run .
```

Or against a different server:

```bash
MOCKSERVER_HOST=mockserver.internal MOCKSERVER_PORT=1080 go run .
```

The program exits `0` only if every check passes, and non-zero otherwise.

## Expected output

```
PASS: object_callback
PASS: class_callback

All callback checks passed.
```
