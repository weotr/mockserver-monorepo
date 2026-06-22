# Scenario Examples

## What it demonstrates

Stateful (state-machine) scenarios with the MockServer Node client. The single
runnable program runs five canonical scenarios in sequence, resetting the server
before each, exercising the data plane with Node's built-in `http` module, and
asserting every outcome:

| Scenario | Demonstrates |
|----------|-------------|
| `state_machine` | A login flow: `GET /profile` is `401` until `POST /login` advances the `LoginFlow` scenario from `Started` to `LoggedIn`, after which `GET /profile` returns `Alice`. Uses the typed `scenarioName` / `scenarioState` / `newScenarioState` expectation fields. |
| `sequential_cycling` | One expectation with multiple responses (`httpResponses` + `responseMode: SEQUENTIAL`) returned in order and then cycling: `200, 503, 200, 200, ...`. No scenario state required. |
| `timed_transition` | The scenario REST helper with a timed auto-transition: `client.scenario("DeployFlow").set("Deploying", { transitionAfterMs: 1000, nextState: "Deployed" })`. `GET /status` returns `deploying`, then `complete` once the scenario auto-advances. |
| `external_trigger` | The scenario REST helper triggered externally: `client.scenario("HealthFlow").trigger("Down")` flips `GET /health` from `200 healthy` to `503 down`. |
| `cross_protocol` | A `crossProtocolScenarios` trigger: a `GET /events` request advances the `ConnFlow` scenario to `Connected`, which unlocks `GET /api/status`. The same mechanism advances scenarios from `DNS_QUERY` / `WEBSOCKET_CONNECT` / `GRPC_REQUEST` events. |

## Prerequisites

- Node.js
- `npm install` (installs `mockserver-client`)
- A MockServer running and reachable. By default the example targets
  `localhost:1080`; override with the `MOCKSERVER_HOST` and `MOCKSERVER_PORT`
  environment variables.

## Run

```bash
npm install
node scenario.js
```

Against a MockServer on a different host/port:

```bash
MOCKSERVER_HOST=mockserver MOCKSERVER_PORT=1080 node scenario.js
```

The program prints a `PASS:` line per scenario and exits `0` only if all five
pass; it exits non-zero and prints a `FAIL:` line on the first failure.

## Expected output

```
Running scenario examples against http://localhost:1080

PASS: state_machine
PASS: sequential_cycling
PASS: timed_transition
PASS: external_trigger
PASS: cross_protocol

All scenarios passed.
```
