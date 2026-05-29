# MockServer k6 performance harness

[k6](https://k6.io) load tests for MockServer. Replaces the legacy Locust harness
(kept for one release under `../legacy/`). k6 gives native Prometheus
remote-write output, built-in pass/fail **thresholds** (CI regression gates), and
Grafana-native dashboards.

## Scripts

| Script | Purpose | Gates |
|--------|---------|-------|
| `smoke.js` | Wiring sanity — exercises every action once (match, create, forward, large-body, regex). Fast. | Correctness only (status codes + zero transport errors). No latency gate. |
| `load.js` | Primary load test. Closed-loop arrival-rate: `match` (GET /simple) ramps to peak while `create` (PUT expectation) churns the control plane. | p95/p99 latency + error-rate + check-rate (CI pass/fail). |
| `stress.js` | Ramp the match path past the load peak to find the breaking point. | Aborts only if error rate exceeds the ceiling (latency intentionally ungated). |
| `soak.js` | Sustained moderate load over a long duration to surface memory/GC/connection leaks. Pair with the Grafana stack to watch JVM heap. | p99 drift + error-rate. |

## Running

```bash
# against a local MockServer on :1080 (default)
k6 run mockserver-performance-test/k6/smoke.js
k6 run mockserver-performance-test/k6/load.js

# point at another instance / protocol
k6 run -e BASE_URL=https://localhost:1080 mockserver-performance-test/k6/load.js
k6 run -e MOCKSERVER_HOST=host.docker.internal:1080 .../load.js

# shape the load / relax gates on a slow agent
k6 run -e K6_PEAK_RATE=1000 -e K6_P95_MS=40 .../load.js
```

## Environment variables

All tunables are env-driven (see `lib/config.js`). Connection target resolves as
`BASE_URL` → else `MOCKSERVER_PROTOCOL` + `MOCKSERVER_HOST` (bare host gets `:1080`).

| Variable | Default | Meaning |
|----------|---------|---------|
| `BASE_URL` | – | Full base URL, e.g. `https://mockserver:1080` (wins over the pair below) |
| `MOCKSERVER_PROTOCOL` | `http` | `http` or `https` |
| `MOCKSERVER_HOST` | `localhost:1080` | host[:port] (mirrors the Locust variable) |
| `INSECURE_SKIP_TLS_VERIFY` | local→`true`, public→`false` | Skip TLS verification. Defaults insecure only for loopback/private hosts (MockServer uses a self-signed CA); set explicitly to `true` for a public HTTPS target. |
| `K6_START_RATE` / `K6_PEAK_RATE` | `50` / `500` | load.js ramping-arrival-rate (req/s) |
| `K6_RAMP_UP` / `K6_HOLD` / `K6_RAMP_DOWN` | `30s` / `1m` / `15s` | load.js stage durations (compound forms like `1m30s` supported) |
| `K6_CREATE_RATE` | `10` | control-plane create-expectation rate (req/s) |
| `K6_STRESS_PEAK_RATE` | `5000` | stress.js peak target (req/s) |
| `K6_SOAK_RATE` / `K6_SOAK_DURATION` | `200` / `30m` | soak.js sustained rate + duration |
| `K6_P95_MS` / `K6_P99_MS` | `25` / `100` | latency thresholds (ms) |
| `K6_MAX_ERROR_RATE` / `K6_MIN_CHECK_RATE` | `0.01` / `0.99` | error/check-rate thresholds |
| `K6_PRE_VUS` / `K6_MAX_VUS` | `50` / `600` | VU pool for the arrival-rate executors |

## Seeded expectations

`setup()` seeds the same 4 expectations as the legacy harness (the request
matches the **last** one, so the matcher does a near-full scan — the realistic
worst case), plus a large-body JSON expectation and a regex-path expectation for
the body-decode and regex scenarios.

> **Note — the `/forward` expectation self-loops.** It uses
> `httpOverrideForwardedRequest` to proxy `/forward` → `/simple` on
> `127.0.0.1:1080`, i.e. MockServer forwards to **itself**. This intentionally
> exercises the proxy/override path on a single instance, but requires the
> instance to be reachable at `127.0.0.1:1080` from inside its own container.
> The `forward` action is only used by `smoke.js`.

## Prometheus / Grafana output

```bash
k6 run -o experimental-prometheus-rw \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
  mockserver-performance-test/k6/load.js
```

The docker-compose stack (`../stack/`) wires k6 → Prometheus → Grafana and also
scrapes MockServer's own `/mockserver/metrics`.
