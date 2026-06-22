# Stateful Scenarios

## What it demonstrates

How to use the MockServer PHP client's stateful-scenario features. The script
runs the 5 canonical scenarios in sequence, exercises each through MockServer's
data plane (with `curl`), and asserts the outcome:

1. **state_machine** — a login flow using `scenarioName` / `scenarioState` /
   `newScenarioState`: `GET /profile` returns 401 until `POST /login` advances
   the `LoginFlow` state machine from `Started` to `LoggedIn`, after which
   `GET /profile` returns Alice.
2. **sequential_cycling** — a single expectation with multiple `httpResponses`
   served in `SEQUENTIAL` mode (the default): four calls to `GET /api/status`
   return `200, 503, 200, 200` (the 4th cycles back to the first response).
3. **timed_transition** — the scenario REST helper with a timed auto-transition:
   `scenario('DeployFlow')->set('Deploying', 1000, 'Deployed')` flips `GET /status`
   from `deploying` to `complete` after one second.
4. **external_trigger** — the scenario REST helper with an external trigger:
   `scenario('HealthFlow')->trigger('Down')` flips `GET /health` from `200 healthy`
   to `503 down`.
5. **cross_protocol** — `crossProtocolScenarios`: a `GET /events` HTTP request
   advances the `ConnFlow` scenario to `Connected`, which makes
   `GET /api/conn-status` (previously unmatched, 404) return `200 connected`.

   > The same mechanism advances scenarios from `DNS_QUERY`,
   > `WEBSOCKET_CONNECT`, and `GRPC_REQUEST` events — change the
   > `CrossProtocolTrigger` constant to fire on a different protocol.

## Prerequisites

- PHP 8.1+
- Composer dependencies installed (`cd ../../../mockserver-client-php && composer install`)
- MockServer running on `localhost:1080`
  (`docker run -d -p 1080:1080 mockserver/mockserver`)

The server location is read from the `MOCKSERVER_HOST` (default `localhost`) and
`MOCKSERVER_PORT` (default `1080`) environment variables.

## Run

```bash
php scenario.php
```

The script resets MockServer before each scenario, so it is self-contained and
order-independent. It exits `0` only if all five scenarios pass.

## Expected output

```
Running stateful-scenario examples against http://localhost:1080

PASS: state_machine
PASS: sequential_cycling
PASS: timed_transition
PASS: external_trigger
PASS: cross_protocol

All 5 scenarios passed.
```
