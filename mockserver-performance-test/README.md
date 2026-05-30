# mockserver-performance-test

Performance / load test suite for [MockServer](https://mock-server.com).

The suite uses [**k6**](https://k6.io) — see [`k6/README.md`](k6/README.md) for
the scripts (smoke / load / stress / soak), the environment-variable contract,
and how to run them. k6 gives native Prometheus remote-write output, built-in
pass/fail **thresholds** (CI regression gates), and Grafana-native dashboards.

## Layout

| Path | What |
|------|------|
| [`k6/`](k6/) | the k6 harness (current) — `smoke.js`, `load.js`, `stress.js`, `soak.js`, shared `lib/` |
| [`scripts/`](scripts/) | `runMockServer.sh` (start MockServer), `runK6.sh` (run a scenario), `runAll.sh` (both) |
| [`legacy/`](legacy/) | the retired Locust harness, kept one release for reference |

## Quick start

```bash
# start a MockServer container and run the load test against it
scripts/runAll.sh

# or, with a local k6 binary, run a single scenario against a running MockServer
k6 run k6/smoke.js
k6 run k6/load.js
```

CI lints the harness on every change (`k6 inspect`); a full load run is an
opt-in / scheduled Buildkite step. See
[`.buildkite/pipeline-perf-test.yml`](../.buildkite/pipeline-perf-test.yml).

## Historical results

Published MockServer performance figures (measured historically with Apache
Benchmark and Locust) live on the consumer site:
<https://www.mock-server.com/mock_server/performance.html>. Numbers are
agent-class dependent — re-measure with the k6 `load.js` thresholds for your
own environment rather than treating any figure as canonical.
