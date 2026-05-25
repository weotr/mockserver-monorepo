# Performance Tuning

Internal companion to the user-facing [Performance page](https://www.mock-server.com/mock_server/performance.html) and [Performance Configuration include](https://www.mock-server.com/mock_server/configuration_properties.html#performance). Where the website tells users *what knobs exist*, this doc explains *why each knob behaves the way it does*, points at the implementation, and captures the rules-of-thumb that aren't worth publishing externally.

## Where the budget actually goes

Three pools account for almost all of MockServer's CPU and memory under load:

| Pool | Sized by | What it bounds |
|------|----------|----------------|
| Netty event loops (server + outbound client) | `nioEventLoopThreadCount`, `clientNioEventLoopThreadCount` | Concurrent socket I/O — incoming connections, proxied outbound, SSE/WebSocket fan-out |
| Action-handler executor | `actionHandlerThreadCount` | Synchronous response/forward/callback dispatch off the event loop |
| LMAX Disruptor ring buffer + log retention | `maxLogEntries` (auto-derived from heap), `maxExpectations` | Recorded requests, verification log, persistent event store |

Read [docs/code/memory-management.md](../code/memory-management.md) before touching `maxLogEntries` / `maxExpectations` — those defaults are derived from heap size at first read, so changing heap without setting the limits explicitly can quietly shift them by an order of magnitude.

For the request-processing flow itself, [docs/code/request-processing.md](../code/request-processing.md) and [docs/code/netty-pipeline.md](../code/netty-pipeline.md) describe where each handler runs and how requests cross the event-loop / executor boundary.

## Rules of thumb

These are the heuristics maintainers reach for when tuning real workloads. They are not contractual — measure before you change.

- **CPU-bound matching → grow `nioEventLoopThreadCount`** to `2 × cores` and leave `actionHandlerThreadCount` alone. Useful when most requests resolve from matchers without action dispatch (read-heavy verification workloads).
- **Action-heavy → grow `actionHandlerThreadCount`** to `4 × cores` and keep event loops at default. Useful when actions block on outbound IO (forwards, callbacks, JavaScript template evaluation).
- **Recording-heavy → raise heap before raising `maxLogEntries`.** The default is derived from heap; doubling `maxLogEntries` without giving the JVM more memory just shifts the eviction pressure into GC overhead.
- **Streaming heavy (SSE / WebSocket fan-out) → check the outbound event-loop count first** (`clientNioEventLoopThreadCount` and `webSocketClientEventLoopThreadCount`); the inbound side rarely saturates first.
- **mTLS or per-cert renegotiation in the hot path → enable `proactivelyInitialiseTLS=true`.** Defers nothing to first-connect; turn-on cost is one slow startup, ongoing cost is zero per-connection.

## JVM flags

The maven CI build agent invokes the JVM with `-Xms2048m -Xmx6144m` (see `scripts/buildkite_quick_build.sh`). For production-like load testing, set both `-Xms` and `-Xmx` to the same value to avoid heap-resize stalls during the run. The shipped Dockerfiles do not set heap defaults — `JAVA_OPTS` from the container environment wins.

A heap dump on OOM is *not* enabled by default; for triage runs add `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/mockserver/` via `JAVA_OPTS`.

## Measuring

Two enable-it-once-then-leave-it knobs:

- `mockserver.metricsEnabled=true` exposes Prometheus metrics at `GET /mockserver/metrics`. The exposed metrics are listed in [docs/code/metrics.md](../code/metrics.md). Always enable this for any non-trivial perf investigation — Buildkite agents have it off by default to avoid skew.
- `mockserver.outputMemoryUsageCsv=true` writes per-second JVM memory snapshots to `memoryUsage_<yyyy-MM-dd>.csv` in the working directory (or `memoryUsageCsvDirectory` if set). Useful when reproducing a leak: grep for the heap line, plot it, and you get the same data the dashboard summary shows.

The repo has a Locust harness in `mockserver-performance-test/`. Use it as a starting point for your own scenarios — don't read the numbers as canonical (they're agent-class-dependent).

## What's deliberately not tuned

These look like knobs but are not — changing them rarely helps and often hurts:

- **Ring buffer size** — directly tied to `maxLogEntries` via `nextPowerOfTwo`; do not try to size them independently. The Disruptor needs the power-of-two for its index masking.
- **Surefire parallel test forks in CI** — currently `parallel=classes, threadCount=4` for `mockserver-core` only. Other modules have shared static state that breaks with multiple forks; the testing-improvements plan tracks the gradual extension. Raising forkCount in CI without doing that work first will produce flakes.
- **`matchersFailFast`** — defaults to `true` (early-exit on first non-matching field) and that is almost always right. Disable only when you specifically need every field's match status in the failure log.

## When perf regresses unexpectedly

1. Check if `metricsEnabled` is on in the affected environment. If not, turn it on and re-run.
2. Look at `mockserver_action_count_total` by action type — a regression in one action category usually points at the responsible code path.
3. Compare ring-buffer drop counters (`mockserver_log_entries_dropped_total`) to baseline. Any non-zero value means log retention is the bottleneck, not the request path.
4. If event loops are pegged, take a thread dump (`jcmd <pid> Thread.print > dump.txt`) and look for stack traces parked on outbound IO — usually a slow downstream, not a MockServer bug.
5. Pull a JFR recording (`-XX:StartFlightRecording=filename=mockserver.jfr,duration=2m`) if you need allocation-level detail. Open in Mission Control or the IntelliJ profiler.
