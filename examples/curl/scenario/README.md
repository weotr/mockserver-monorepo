# Stateful Scenario Examples

## What it demonstrates

Five self-contained shell scripts that exercise every stateful-scenario
primitive available in MockServer's REST API. Each script resets MockServer
before running, makes the relevant API calls, and asserts the observed
behaviour — exiting non-zero on failure.

| Script | Demonstrates |
|--------|--------------|
| `state_machine.sh` | A login flow driven by `scenarioName` / `scenarioState` / `newScenarioState`. `GET /profile` returns 401 until `POST /login` advances the `LoginFlow` scenario from `Started` to `LoggedIn`, after which it returns the user. |
| `sequential_cycling.sh` | One expectation with multiple responses in `SEQUENTIAL` mode (`httpResponses` array). Three calls return 200, 503, 200; the fourth cycles back to the first. |
| `timed_transition.sh` | Uses `PUT /mockserver/scenario/DeployFlow` to set state `Deploying` with an auto-transition to `Deployed` after 1000 ms. `GET /status` returns `deploying`, then `complete` after the window. |
| `external_trigger.sh` | Uses `PUT /mockserver/scenario/HealthFlow/trigger` to force `HealthFlow` into the `Down` state from outside the data plane. `GET /health` returns `healthy` until the trigger fires. |
| `cross_protocol.sh` | Uses `crossProtocolScenarios` on an HTTP_REQUEST trigger: a request to `GET /events` advances `ConnFlow` to `Connected`, unlocking `GET /api/conn-status`. |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed

## Run

Scripts honour `MOCKSERVER_URL`, falling back to `MOCKSERVER_HOST` / `MOCKSERVER_PORT`
(defaults: `localhost` / `1080`):

```bash
MOCKSERVER_URL=http://localhost:1080 bash state_machine.sh
MOCKSERVER_URL=http://localhost:1080 bash sequential_cycling.sh
MOCKSERVER_URL=http://localhost:1080 bash timed_transition.sh
MOCKSERVER_URL=http://localhost:1080 bash external_trigger.sh
MOCKSERVER_URL=http://localhost:1080 bash cross_protocol.sh
```

Or with host/port overrides:

```bash
MOCKSERVER_HOST=mockserver.internal MOCKSERVER_PORT=1080 bash state_machine.sh
```

Each script exits `0` only if every assertion passes, non-zero otherwise.
