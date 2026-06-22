# Scenario

## What it demonstrates

MockServer's **stateful-scenario** features. The example runs all 5 canonical
scenarios in sequence, exercising the data plane with `Net::HTTP` and asserting
each outcome:

1. **state_machine** -- a login flow driven by `scenario_state` /
   `new_scenario_state` transitions on expectations (`GET /profile` returns 401
   until `POST /login` advances the `LoginFlow` scenario to `LoggedIn`).
2. **sequential_cycling** -- one expectation with multiple `http_responses` in
   `SEQUENTIAL` `response_mode`, cycling `200 -> 503 -> 200` and back to the
   first response on the 4th call.
3. **timed_transition** -- the `scenario(name).set(state, transition_after_ms:,
   next_state:)` control-plane helper schedules a timed auto-transition from
   `Deploying` to `Deployed`.
4. **external_trigger** -- the `scenario(name).trigger(new_state)` control-plane
   helper flips a health check from `200 healthy` to `503 down`.
5. **cross_protocol** -- a `cross_protocol_scenarios` correlation: a
   `GET /events` (HTTP_REQUEST trigger) advances the `ConnFlow` scenario to
   `Connected`, which unlocks `GET /api/conn-status`. The same mechanism
   advances scenarios from `DNS_QUERY` / `WEBSOCKET_CONNECT` / `GRPC_REQUEST`
   events.

The server is reset before each scenario, so the example is self-contained and
order-independent. It prints `PASS: <scenario>` per scenario and exits non-zero
if any assertion fails.

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080` (or set `MOCKSERVER_HOST` /
  `MOCKSERVER_PORT`)

## Run

```bash
ruby scenario.rb
```

Point at a different server with environment variables:

```bash
MOCKSERVER_HOST=mockserver MOCKSERVER_PORT=1080 ruby scenario.rb
```

## Expected output

```
PASS: state_machine
PASS: sequential_cycling
PASS: timed_transition
PASS: external_trigger
PASS: cross_protocol

All scenarios passed.
```
