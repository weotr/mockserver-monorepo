# Load Scenario Registry (JSON bodies)

Raw request-body payloads for MockServer's **Load Scenario registry** REST API.
A load scenario is a named, server-side traffic generator (register once, then
start/stop by name). These are the JSON bodies you `PUT` to the registry
endpoints; the curl scripts in [`../../curl/load_scenario/`](../../curl/load_scenario/)
post the same files.

| File | Endpoint | Demonstrates |
|------|----------|--------------|
| `checkout_load.json` | `PUT /mockserver/loadScenario` | A realistic multi-stage scenario: a linear **RATE ramp** (5→50 req/s over 30s, capped at 50 VUs), a 25-VU **hold** for 60s, then a 10s **PAUSE**. Two Velocity-templated steps (`browse`, `checkout`), each embedding a full `HttpRequest`, with `thinkTime`, `labels` and `maxRequests`. |
| `demo_soak.json` | `PUT /mockserver/loadScenario` | A minimal single-step soak scenario (RATE ramp → VU hold → PAUSE driving `GET /ping`). |
| `start_single.json` | `PUT /mockserver/loadScenario/start` | Start one scenario by `{"name":"checkout-load"}`. |
| `start_names.json` | `PUT /mockserver/loadScenario/start` | Start several at once with `{"names":[...]}`. |
| `stop_all.json` | `PUT /mockserver/loadScenario/stop` | Stop every running scenario with `{"all":true}` (an empty `{}` body does the same). |

## Prerequisites

- MockServer **started with load generation enabled** (required to *start*
  scenarios — registering is always allowed):

  ```bash
  java -Dmockserver.loadGenerationEnabled=true \
    -jar mockserver-netty-jar-with-dependencies.jar -serverPort 1080
  ```

  Starting without it returns `403`
  `{"error":"load generation not enabled (set loadGenerationEnabled=true)"}`.
- `curl` installed. Set `U` to your base URL:

  ```bash
  U="${MOCKSERVER_URL:-http://localhost:1080}"
  ```

## Apply the JSON payloads

```bash
# Register both scenarios
curl -X PUT "$U/mockserver/loadScenario" --data @checkout_load.json
curl -X PUT "$U/mockserver/loadScenario" --data @demo_soak.json

# Start them
curl -X PUT "$U/mockserver/loadScenario/start" --data @start_names.json

# Inspect live status
curl "$U/mockserver/loadScenario"                       # list all
curl "$U/mockserver/loadScenario/checkout-load"         # one scenario

# Stop everything, then clear the registry
curl -X PUT "$U/mockserver/loadScenario/stop" --data @stop_all.json
curl -X DELETE "$U/mockserver/loadScenario"
```

## Notes

- **Stage types:** `RATE` (arrival rate — `rate`, or `startRate`/`endRate` with
  optional `maxVus`), `VU` (concurrent users — `vus`, or `startVus`/`endVus`),
  `PAUSE` (no load). `curve` ∈ `LINEAR` (default) / `QUADRATIC` / `EXPONENTIAL`.
- **Per-step `request`** is a standard MockServer `HttpRequest` — note the field
  is `request`, not `httpRequest`. `templateType` (`VELOCITY`/`MUSTACHE`) renders
  it per iteration (e.g. `$!iteration.index`).
- **Safety caps** apply: by default VUs ≤ 50 and rate ≤ 500 req/s; exceeding a
  cap returns `400`. Adjust the `mockserver.loadGeneration*` properties to raise
  them.
