# Interactive Breakpoints

Interactive breakpoints let you pause proxied/forwarded exchanges at two phases:

1. **Request breakpoints** (A1a) — hold the outbound request before it reaches the upstream server
2. **Response breakpoints** (A1b) — hold the upstream response before it is written to the client

Both phases support inspect, modify, continue, and abort via the REST API.

## Non-blocking architecture

The breakpoint mechanism is fully asynchronous. When a breakpoint matches:

1. A `PausedExchange` is registered in the `BreakpointRegistry` (a process-wide
   singleton backed by a `ConcurrentHashMap`).
2. The scheduler worker thread returns immediately. The continuation (forward the
   request / write the response, or write an abort response) is chained onto the
   `CompletableFuture<BreakpointDecision>` via `thenAcceptAsync(..., schedulerExecutor)`.
3. The control-plane endpoint (or the timeout scheduler) completes the future,
   which triggers the continuation on a scheduler pool thread.

No thread is blocked while waiting for the decision. This avoids exhausting the
`ScheduledThreadPoolExecutor` pool, which uses `CallerRunsPolicy` and would
otherwise run tasks on the Netty event-loop thread (causing a self-inflicted DoS).

## Phases

### Request phase (`breakpointEnabled`)

- Hold point: `HttpActionHandler.handleUnmatchedProxyForward`, after pre-flight
  validation but before the upstream HTTP call.
- Decision actions: CONTINUE (forward original), MODIFY (forward replacement
  request), ABORT (write error response to client without forwarding).

### Response phase (`breakpointResponseEnabled`)

- Hold point: `HttpActionHandler.writeForwardActionResponse` (expectation-matched
  forwards) and `executeUnmatchedForward` (unmatched proxy forwards), after the
  upstream response is received but before it is written to the client.
- Non-streaming (buffered) responses only — streaming responses are written
  immediately (breakpoint is skipped).
- Decision actions: CONTINUE (write original response), MODIFY (write replacement
  response), ABORT (write error response).
- The upstream `HttpResponse` is a deserialized model object (no pooled ByteBuf),
  so parking does not risk use-after-free.

## Safety rails

- **Timeout auto-continue:** each paused exchange auto-continues if not resolved
  within `breakpointTimeoutMillis` (default 30 seconds).
- **Max-held cap:** when `breakpointMaxHeld` (default 50) exchanges are held
  (shared across both phases), new intercepts are skipped.
- **Default off:** both `breakpointEnabled` and `breakpointResponseEnabled`
  default to `false` — zero overhead.

## Control-plane endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET/PUT | `/mockserver/breakpoint` | List all currently paused exchanges (includes `phase` field) |
| PUT | `/mockserver/breakpoint/continue` | Continue a paused exchange |
| PUT | `/mockserver/breakpoint/modify` | Modify: `{id, httpRequest}` for request phase, `{id, httpResponse}` for response phase |
| PUT | `/mockserver/breakpoint/abort` | Abort: write error response to client |

The list endpoint includes a `phase` field (`REQUEST` or `RESPONSE`) and, for
response-phase exchanges, a `response` summary with `statusCode` and `reasonPhrase`.

## Configuration properties

| Property | Default | Description |
|----------|---------|-------------|
| `mockserver.breakpointEnabled` | `false` | Enable request-phase breakpoints |
| `mockserver.breakpointResponseEnabled` | `false` | Enable response-phase breakpoints |
| `mockserver.breakpointTimeoutMillis` | `30000` | Auto-continue timeout (shared) |
| `mockserver.breakpointMaxHeld` | `50` | Max concurrent paused exchanges (shared) |

## Key classes

- `BreakpointRegistry` — process-wide singleton managing paused exchanges
- `PausedExchange` — holds phase, captured request/response, `CompletableFuture<BreakpointDecision>`
- `BreakpointDecision` — CONTINUE / MODIFY (request or response) / ABORT resolution
- `HttpActionHandler.handleUnmatchedProxyForward` — request-phase breakpoint intercept
- `HttpActionHandler.writeForwardActionResponse` — response-phase breakpoint intercept (matched)
- `HttpActionHandler.executeUnmatchedForward` — response-phase breakpoint intercept (unmatched)
- `HttpState.handleBreakpointContinue/Modify/Abort` — control-plane handlers

## Behavioural notes

- **Response chaos-latency is not re-applied after manual resolution.** When a
  response breakpoint is resolved (CONTINUE, MODIFY, or ABORT), any configured
  response chaos-latency for the matched expectation is bypassed. The manual
  resolution supersedes automatic chaos injection because the user has already
  inspected and approved (or replaced) the response.
- **`httpResponse` takes precedence in the modify endpoint.** If a client sends
  both `httpRequest` and `httpResponse` fields in a modify payload, the
  `httpResponse` field is used (response-phase modify). The `httpRequest` field
  is silently ignored for response-phase exchanges.
- **Phase guards prevent type-confusion.** `resolveModify(id, httpRequest)` is
  rejected (returns false) if the exchange is in RESPONSE phase, and
  `resolveModifyResponse(id, httpResponse)` is rejected if the exchange is in
  REQUEST phase. This prevents completing a decision future with the wrong type,
  which would cause a downstream NPE.

## Follow-up (not yet implemented)

- INC-11: Java client-library (`MockServerClient`) methods for the breakpoint endpoints
