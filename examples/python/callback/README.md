# Callback Examples

## What it demonstrates

The two kinds of MockServer callback from the Python client. The script runs
both examples in sequence, resetting MockServer before each, and asserts the
observed behaviour:

- **object_callback** -- an *object* (closure) callback via
  `client.mock_with_callback(request, handler)`. The client opens a callback
  WebSocket, the server hands it a `clientId`, and on every match the server
  streams the request to the Python closure, which writes the response. The
  closure here derives the response **dynamically from the request** (echoing
  the request path/method and an `X-Who` header), so the same expectation
  returns different bodies for different requests.

- **class_callback** -- a *class* callback (REST-only, no WebSocket) via
  `client.when(request).respond_with_class_callback("com.example.MyCallback")`
  (and the forward variant
  `.forward_with_class_callback(...)`). The expectation references a server-side
  class by fully-qualified name. The class need not exist for the server to
  accept and persist the expectation; the example asserts the upsert succeeds
  and round-trips the wire shape (`httpResponseClassCallback` /
  `httpForwardClassCallback`). Both an `HttpClassCallback` object and a plain
  class-name `str` are accepted.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080` (override with `MOCKSERVER_HOST` /
  `MOCKSERVER_PORT`)

## Run

```bash
python callback.py
```

The server location is read from `MOCKSERVER_HOST` (default `localhost`) and
`MOCKSERVER_PORT` (default `1080`). The script exits `0` only if every example
passes, non-zero otherwise.

## Expected output

```
PASS: object_callback
PASS: class_callback

All callback examples passed.
```
