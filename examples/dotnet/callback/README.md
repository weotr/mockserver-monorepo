# Callbacks

## What it demonstrates

How to use the MockServer .NET client's two kinds of callback. The program runs
both checks in sequence, resetting the server before each, and asserts every
outcome (it exits non-zero if any check fails):

1. **object_callback** — `client.MockWithCallback(request, closure)` registers a
   C# closure as the response producer. On a match the server dispatches the
   request over the callback WebSocket to this client, the closure derives the
   response from the request (here it echoes the request path into the body and
   an `X-Echo-Path` header), and the server returns it. A real `GET /dynamic`
   data-plane request asserts the dynamically computed `201` response.
2. **class_callback** — `RespondWithClassCallback("...")` registers an
   expectation carrying an `httpResponseClassCallback` that references a
   server-side class. This is pure JSON, REST-only — no WebSocket. The class need
   not exist to register the expectation; the example asserts the control plane
   accepts and stores it (the registered expectation is retrieved and its
   `callbackClass` checked).

### Callback kinds at a glance

| Kind | Where the logic runs | Transport | API |
|------|----------------------|-----------|-----|
| Object (closure) | In your .NET process | Callback WebSocket | `client.MockWithCallback(request, req => response)` |
| Class (declarative) | Server-side class on the MockServer classpath | REST (JSON only) | `When(request).RespondWithClassCallback("com.example.MyCallback")` |

Forward variants also exist: `client.MockWithForwardCallback(request, req => forwardedRequest)`
and `When(request).ForwardWithClassCallback("...")`.

The object callback reuses the single callback WebSocket shared with breakpoints —
no second socket is opened per client.

## Prerequisites

- .NET SDK 8.0+
- MockServer running (defaults to `localhost:1080`)

The server location is read from the environment:

- `MOCKSERVER_HOST` (default `localhost`)
- `MOCKSERVER_PORT` (default `1080`)

## Run

```bash
dotnet run
```

Or against a different server:

```bash
MOCKSERVER_HOST=mockserver MOCKSERVER_PORT=1080 dotnet run
```

## Expected output

```
PASS: object_callback
PASS: class_callback

All callback checks passed.
```
