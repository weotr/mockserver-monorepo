# Pact with Provider States (JSON)

## What it demonstrates

A **Pact v3 contract whose single interaction is gated by a provider state**,
ready to import via `PUT /mockserver/pact/import`. The interaction declares a
`providerStates` precondition (`given a user with id 1 exists`); on import
MockServer gates the generated expectation on a scenario named
`pact-provider-state`, so the interaction only matches once that provider state
has been activated.

| File | Description |
|------|-------------|
| `pact_with_provider_state.json` | A Pact v3 contract with one provider-state-gated interaction |

Provider states are recognised in both wire forms:

| Pact version | Field | Shape |
|--------------|-------|-------|
| v3 | `providerStates` | array of `{ "name": "...", "params": { ... } }` (the `name` gates matching) |
| v2 | `providerState` | a single string |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed (to send the payload)

## Run

```bash
# Import the contract
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/pact/import" \
  -H "Content-Type: application/json" \
  -d @pact_with_provider_state.json

# Activate the provider state so the gated interaction matches
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/scenario/pact-provider-state" \
  -H "Content-Type: application/json" \
  -d '{"state": "a user with id 1 exists"}'
```

## Expected output

The import returns `201 Created` with the generated expectation as JSON; it
carries the `pact-provider-state` scenario gate. After activating the scenario
(`200`), `GET /api/users/1` returns **200** with `{"id":1,"name":"Alice"}`;
before activation it returns **404**.
