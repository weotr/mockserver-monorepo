# Request Breakpoints

Interactive request breakpoints let you pause proxied/forwarded requests before
they reach the upstream server, inspect or modify them via the REST API, then
continue, modify, or abort the request.

## Non-blocking architecture

The breakpoint mechanism is fully asynchronous. When a breakpoint matches:

1. A `PausedExchange` is registered in the `BreakpointRegistry` (a process-wide
   singleton backed by a `ConcurrentHashMap`).
2. The scheduler worker thread returns immediately. The continuation (forward the
   request or write an abort response) is chained onto the `CompletableFuture<BreakpointDecision>`
   via `thenAcceptAsync(..., schedulerExecutor)`.
3. The control-plane endpoint (or the timeout scheduler) completes the future,
   which triggers the continuation on a scheduler pool thread.

No thread is blocked while waiting for the decision. This avoids exhausting the
`ScheduledThreadPoolExecutor` pool, which uses `CallerRunsPolicy` and would
otherwise run tasks on the Netty event-loop thread (causing a self-inflicted DoS).

## Safety rails

- **Timeout auto-continue:** each paused exchange auto-continues if not resolved
  within `breakpointTimeoutMillis` (default 30 seconds).
- **Max-held cap:** when `breakpointMaxHeld` (default 50) exchanges are held,
  new requests bypass the breakpoint and are forwarded normally.
- **Default off:** `breakpointEnabled` defaults to `false` — zero overhead.

## Control-plane endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET/PUT | `/mockserver/breakpoint` | List all currently paused exchanges |
| PUT | `/mockserver/breakpoint/continue` | Continue a paused exchange (forward original) |
| PUT | `/mockserver/breakpoint/modify` | Forward a replacement request |
| PUT | `/mockserver/breakpoint/abort` | Do not forward; return an abort response |

## Configuration properties

| Property | Default | Description |
|----------|---------|-------------|
| `mockserver.breakpointEnabled` | `false` | Master switch |
| `mockserver.breakpointTimeoutMillis` | `30000` | Auto-continue timeout |
| `mockserver.breakpointMaxHeld` | `50` | Max concurrent paused exchanges |

## Key classes

- `BreakpointRegistry` — process-wide singleton managing paused exchanges
- `PausedExchange` — holds the captured request + `CompletableFuture<BreakpointDecision>`
- `BreakpointDecision` — CONTINUE / MODIFY / ABORT resolution
- `HttpActionHandler.handleUnmatchedProxyForward` — async breakpoint intercept point
- `HttpState.handleBreakpointContinue/Modify/Abort` — control-plane handlers

## Follow-up (not yet implemented)

- INC-11: Java client-library (`MockServerClient`) methods for the breakpoint endpoints
