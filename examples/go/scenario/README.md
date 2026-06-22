# Stateful Scenarios

## What it demonstrates

How to use the MockServer Go client's stateful-scenario features. The program runs
five canonical scenarios in sequence, resetting MockServer before each, and asserts
the outcome of every one:

| Scenario | Demonstrates |
|----------|--------------|
| `state_machine` | A login flow driven by `scenarioState` / `newScenarioState`. `GET /profile` returns 401 until `POST /login` advances the `LoginFlow` scenario from `Started` to `LoggedIn`, after which `GET /profile` returns Alice. |
| `sequential_cycling` | One expectation with multiple responses (`RespondMultiple`) in `SEQUENTIAL` mode. Four calls return `200, 503, 200, 200` — the fourth cycles back to the first response. |
| `timed_transition` | The scenario REST helper with a timed auto-transition: `Scenario("DeployFlow").SetTimed("Deploying", 1000, "Deployed")`. `GET /status` returns `deploying`, then `complete` after the transition window. |
| `external_trigger` | The scenario REST helper driven by an external trigger: `Scenario("HealthFlow").Trigger("Down")`. `GET /health` returns `healthy`, then `down` after the trigger. |
| `cross_protocol` | `crossProtocolScenarios`: a `GET /events` request advances scenario `ConnFlow` to `Connected` (an `HTTP_REQUEST` trigger), after which `GET /api/status` matches and returns `connected`. The same mechanism advances scenarios from `DNS_QUERY`, `WEBSOCKET_CONNECT`, and `GRPC_REQUEST` events. |

## Prerequisites

- Go 1.21+
- MockServer running (defaults to `localhost:1080`; e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)

## Run

The server location is read from the environment, defaulting to `localhost:1080`:

- `MOCKSERVER_HOST` (default `localhost`)
- `MOCKSERVER_PORT` (default `1080`)

```bash
go run .
```

Or against a different server:

```bash
MOCKSERVER_HOST=mockserver.internal MOCKSERVER_PORT=1080 go run .
```

The program exits `0` only if every scenario passes, and non-zero otherwise.

## Expected output

```
PASS: state_machine
PASS: sequential_cycling
PASS: timed_transition
PASS: external_trigger
PASS: cross_protocol

All scenarios passed.
```
