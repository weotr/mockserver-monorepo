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

### GC selection

Java 17 ships production-ready ZGC. For latency-sensitive deployments — particularly those running with large `maxLogEntries` (deep event ring buffers) — `-XX:+UseZGC` typically holds stop-the-world pauses in the single-digit millisecond range (1–5 ms) regardless of heap size, where G1 (the Java 17 server-class default) commonly sits in the 50–200 ms range during mixed cycles under sustained allocation. (Sub-millisecond pauses are an attribute of Generational ZGC in JDK 21+, not the non-generational ZGC shipped in Java 17.)

These numbers are based on typical GC behaviour, not MockServer-specific benchmarks. Use the `mockserver-performance-test/` Locust harness with `mockserver.outputMemoryUsageCsv=true` to confirm your workload before switching.

Rules of thumb:
- **Heap < 2 GB:** stay on the default (G1). ZGC's fixed overhead isn't worth it.
- **Heap 2–4 GB:** G1 (the default) is fine for almost everything. Switch to ZGC only if you've measured GC pauses showing up on the matcher path.
- **Heap ≥ 4 GB and p99 latency matters:** add `-XX:+UseZGC` via `JAVA_OPTS`. Set `-Xms` and `-Xmx` to the same value (e.g. `-Xms4g -Xmx4g`) so the heap is pre-committed.

In containerised deployments, size the container memory limit at least ~1.5× the `-Xmx` value when using ZGC. The kernel OOM-killer reacts to physical memory (RSS), not virtual address space — what eats RSS beyond `-Xmx` is the JVM's own overhead (code cache, metaspace, JIT, thread stacks) plus Netty's direct buffer pool. ZGC adds a further wrinkle on some cgroup setups: it multi-maps the same physical pages for its coloured-pointer scheme, and under certain RSS-accounting modes those pages are counted multiple times against the cgroup limit, so the kernel can OOM-kill the process even though the actual physical footprint fits. Example: `-Xmx4g` → `--memory=6g`.

ZGC is not the default because (a) MockServer's typical deployment is a small fixture in a test pipeline where G1 is fine, and (b) ZGC adds a fixed memory overhead that hurts small-heap scenarios.

Shenandoah is deliberately omitted: it has been production-ready since OpenJDK 15 (JEP 379) and is therefore available in OpenJDK 17, but it is absent from Oracle JDK 17 and not universally available across all JDK distributions. ZGC is the simpler recommendation because it ships in every JDK 17 distribution MockServer supports.

## Measuring

Two enable-it-once-then-leave-it knobs:

- `mockserver.metricsEnabled=true` exposes Prometheus metrics at `GET /mockserver/metrics`. The exposed metrics are listed in [docs/code/metrics.md](../code/metrics.md). Always enable this for any non-trivial perf investigation — Buildkite agents have it off by default to avoid skew.
- `mockserver.outputMemoryUsageCsv=true` writes per-second JVM memory snapshots to `memoryUsage_<yyyy-MM-dd>.csv` in the working directory (or `memoryUsageCsvDirectory` if set). Useful when reproducing a leak: grep for the heap line, plot it, and you get the same data the dashboard summary shows.

The repo has a Locust harness in `mockserver-performance-test/`. Use it as a starting point for your own scenarios — don't read the numbers as canonical (they're agent-class-dependent).

## What's deliberately not tuned

These look like knobs but are not — changing them rarely helps and often hurts:

- **Ring buffer size** — directly tied to `maxLogEntries` via `nextPowerOfTwo`; do not try to size them independently. The Disruptor needs the power-of-two for its index masking.
- **Surefire parallel test forks in CI** — explicitly disabled. `parallel=classes, threadCount=4` was attempted on `mockserver-core` but caused a JVM `<clinit>` deadlock when concurrent test threads first-touched shared static state in `ConfigurationProperties` / `EchoServer`. Sequential execution is the supported configuration. Do not re-enable.
- **`matchersFailFast`** — defaults to `true` (early-exit on first non-matching field) and that is almost always right. Disable only when you specifically need every field's match status in the failure log.

## When perf regresses unexpectedly

1. Check if `metricsEnabled` is on in the affected environment. If not, turn it on and re-run.
2. Look at `mockserver_action_count_total` by action type — a regression in one action category usually points at the responsible code path.
3. Compare ring-buffer drop counters (`mockserver_log_entries_dropped_total`) to baseline. Any non-zero value means log retention is the bottleneck, not the request path.
4. If event loops are pegged, take a thread dump (`jcmd <pid> Thread.print > dump.txt`) and look for stack traces parked on outbound IO — usually a slow downstream, not a MockServer bug.
5. Pull a JFR recording (`-XX:StartFlightRecording=filename=mockserver.jfr,duration=2m`) if you need allocation-level detail. Open in Mission Control or the IntelliJ profiler.
