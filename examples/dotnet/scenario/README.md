# Stateful Scenarios

## What it demonstrates

How to use the MockServer .NET client's stateful-scenario features. The program
runs all 5 canonical scenarios in sequence, resetting the server before each,
exercising the data plane with `System.Net.Http.HttpClient`, and asserting every
outcome (it exits non-zero if any scenario fails):

1. **state_machine** — a login flow where `GET /profile` returns 401 until
   `POST /login` advances the `LoginFlow` scenario from `Started` to `LoggedIn`,
   after which `GET /profile` returns Alice's profile.
2. **sequential_cycling** — one expectation with multiple `HttpResponses` served
   in `SEQUENTIAL` mode; four calls return `200, 503, 200, 200` (the fourth
   cycles back to the first response).
3. **timed_transition** — `client.Scenario("DeployFlow").Set("Deploying", 1000, "Deployed")`
   sets the state to `Deploying` and auto-transitions to `Deployed` after 1s, so
   `GET /status` flips from `deploying` to `complete`.
4. **external_trigger** — `client.Scenario("HealthFlow").Trigger("Down")`
   externally advances the scenario, flipping `GET /health` from 200 healthy to
   503 down.
5. **cross_protocol** — a `crossProtocolScenarios` trigger on `GET /events`
   (an `HTTP_REQUEST` trigger) advances the `ConnFlow` scenario to `Connected`,
   so `GET /api/conn-status` changes from unmatched (404) to 200 connected. The
   same mechanism advances scenarios from `DNS_QUERY`, `WEBSOCKET_CONNECT` and
   `GRPC_REQUEST` events.

## Prerequisites

- .NET SDK 8.0+
- MockServer running (defaults to `localhost:1080`)

The server location is read from the environment:

- `MOCKSERVER_HOST` (default `localhost`)
- `MOCKSERVER_PORT` (default `1080`)

## Run

```bash
dotnet run
```

Or against a different server:

```bash
MOCKSERVER_HOST=mockserver MOCKSERVER_PORT=1080 dotnet run
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
