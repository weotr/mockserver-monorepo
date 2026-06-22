# Stateful Scenario Examples

## What it demonstrates

Stateful, sequential, and timed mocking with the typed scenario API. The script
runs five canonical scenarios in sequence, resetting MockServer before each, and
asserts the observed behaviour:

- **state_machine** -- a login flow where `POST /login` advances the `LoginFlow`
  scenario from `Started` to `LoggedIn`, changing what `GET /profile` returns
  (`scenario_name` / `scenario_state` / `new_scenario_state`).
- **sequential_cycling** -- a single expectation with several `http_responses`
  served in `ResponseMode.SEQUENTIAL` order (200, 503, 200), cycling back to the
  first on the fourth call.
- **timed_transition** -- `client.scenario("DeployFlow").set("Deploying",
  transition_after_ms=1000, next_state="Deployed")` auto-advances the scenario
  after a delay.
- **external_trigger** -- `client.scenario("HealthFlow").trigger("Down")` forces
  a state change from outside the data plane.
- **cross_protocol** -- a `CrossProtocolScenario` on `GET /events` advances
  `ConnFlow` to `Connected`, unlocking `GET /api/conn-status`. The same mechanism
  advances scenarios from `DNS_QUERY`, `WEBSOCKET_CONNECT`, and `GRPC_REQUEST`
  events.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080` (override with `MOCKSERVER_HOST` /
  `MOCKSERVER_PORT`)

## Run

```bash
python scenario.py
```

The server location is read from `MOCKSERVER_HOST` (default `localhost`) and
`MOCKSERVER_PORT` (default `1080`). The script exits `0` only if every scenario
passes, non-zero otherwise.

## Expected output

```
PASS: state_machine
PASS: sequential_cycling
PASS: timed_transition
PASS: external_trigger
PASS: cross_protocol

All scenarios passed.
```
