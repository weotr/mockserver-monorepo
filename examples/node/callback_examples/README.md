# Callback Examples

## What it demonstrates

The two kinds of MockServer callback with the Node client. The single runnable
program runs the examples in sequence, resetting the server before each, and
asserts every outcome:

| Example | Demonstrates |
|---------|-------------|
| `class_callback_raw` | A raw expectation carrying `httpResponseClassCallback` is accepted (`201` on upsert). A **class callback** is pure JSON / REST-only — no callback WebSocket. It names a server-side class implementing `ExpectationResponseCallback`, resolved inside the MockServer JVM, so this asserts only that the wire shape is stored. |
| `class_callback_helper` | The `client.respondWithClassCallback("/path", "com.example.MyCallback")` convenience method produces the same accepted expectation. |
| `forward_class_callback` | `client.forwardWithClassCallback("/path", "com.example.MyForwardCallback")` registers an `httpForwardClassCallback` expectation. |
| `object_callback` | `client.mockWithCallback(...)` runs a **LOCAL closure** in this process: the client opens the callback WebSocket, the server hands it a `clientId`, and on a match the request is sent over the WebSocket to the closure, which derives the response **from the request**. A real `POST /object/callback` is sent and the dynamic response asserted, exercising Node's object-callback path end-to-end. |

### Class callbacks vs object callbacks

- **Class callbacks** (`httpResponseClassCallback` / `httpForwardClassCallback`)
  are declarative and REST-only. The callback logic lives in a server-side class
  on the MockServer classpath; the client just references it by name.
- **Object/closure callbacks** (`mockWithCallback` /
  `mockWithForwardCallback` / `mockWithForwardAndResponseCallback`) run the
  callback closure in **your** process over the callback WebSocket, so the
  response can be derived dynamically from each request.

## Prerequisites

- Node.js
- `npm install` (installs `mockserver-client`)
- A MockServer running and reachable. By default the example targets
  `localhost:1080`; override with the `MOCKSERVER_HOST` and `MOCKSERVER_PORT`
  environment variables.

> The `class_callback_*` examples only assert the server **accepts** the
> expectation; the named server-side classes
> (`org.mockserver.examples.MyResponseCallback`,
> `org.mockserver.examples.MyForwardCallback`) are placeholders and need not
> exist for the wire shape to be stored.

## Run

```bash
npm install
node scenario.js
```

Against a MockServer on a different host/port:

```bash
MOCKSERVER_HOST=mockserver MOCKSERVER_PORT=1080 node scenario.js
```

The program prints a `PASS:` line per example and exits `0` only if all pass; it
exits non-zero and prints a `FAIL:` line on the first failure.

## Expected output

```
Running callback examples against http://localhost:1080

PASS: class_callback_raw
PASS: class_callback_helper
PASS: forward_class_callback
PASS: object_callback

All callback examples passed.
```
