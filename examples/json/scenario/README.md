# Stateful Scenario Examples

## What it demonstrates

Five JSON payloads covering every stateful-scenario primitive in MockServer's
REST API. Three files are arrays of expectations sent to `PUT /mockserver/expectation`;
two are request bodies for the scenario control endpoints.

| File | Endpoint | Shape | Demonstrates |
|------|----------|-------|--------------|
| `state_machine.json` | `PUT /mockserver/expectation` | array | Three expectations share `scenarioName: "LoginFlow"`. `POST /login` advances `Started` → `LoggedIn`; `GET /profile` matches only in `LoggedIn` state. |
| `sequential_cycling.json` | `PUT /mockserver/expectation` | object | One expectation with an `httpResponses` array in `SEQUENTIAL` mode — returns 200, 503, 200 on successive calls, then cycles. |
| `cross_protocol.json` | `PUT /mockserver/expectation` | array | Two expectations: the first uses `crossProtocolScenarios` to advance `ConnFlow` to `Connected` on `GET /events`; the second gates `GET /api/conn-status` on that state. |
| `timed_transition.json` | `PUT /mockserver/scenario/DeployFlow` | object | Sets state `Deploying` with `transitionAfterMs: 1000` and `nextState: "Deployed"`. Register the state-gated expectations from `state_machine.json` style first. |
| `external_trigger.json` | `PUT /mockserver/scenario/HealthFlow/trigger` | object | Advances `HealthFlow` to state `Down` from outside the data plane. |

## Prerequisites

- A running MockServer instance
- `curl` installed

Set `U` to your MockServer base URL:

```bash
U="${MOCKSERVER_URL:-http://localhost:1080}"
```

## Apply the JSON payloads

### Expectation arrays / objects

```bash
# Login state machine (array of expectations)
curl -sf -X PUT "$U/mockserver/expectation" --data-binary @state_machine.json

# Sequential cycling responses (single expectation object)
curl -sf -X PUT "$U/mockserver/expectation" --data-binary @sequential_cycling.json

# Cross-protocol correlation (array of two expectations)
curl -sf -X PUT "$U/mockserver/expectation" --data-binary @cross_protocol.json
```

### Scenario control endpoints

These bodies go to the scenario REST API, not the expectation endpoint:

```bash
# Timed auto-transition: set DeployFlow to Deploying, auto-advance to Deployed after 1s
curl -sf -X PUT "$U/mockserver/scenario/DeployFlow" --data-binary @timed_transition.json

# External trigger: advance HealthFlow to Down immediately
curl -sf -X PUT "$U/mockserver/scenario/HealthFlow/trigger" --data-binary @external_trigger.json
```
