# Importing a Pact with Provider States (curl)

## What it demonstrates

Importing a **Pact v3 contract whose interaction is gated by a provider state**
via `PUT /mockserver/pact/import`, then activating that provider state so the
generated expectation matches.

A Pact provider state is the interaction's `given ...` precondition. On import,
MockServer maps it onto its scenario-state mechanism: the generated expectation
is gated on a scenario named **`pact-provider-state`** whose required state is
the provider-state name. The interaction therefore only matches once that
provider state has been **activated** (interactions without a provider state are
unaffected and always match).

| Script | Description |
|--------|-------------|
| `import_pact_with_provider_state.sh` | Import a gated interaction, show it not matching, activate the state, then show it matching |

Provider states are recognised in both wire forms:

| Pact version | Field | Shape |
|--------------|-------|-------|
| v3 | `providerStates` | array of `{ "name": "...", "params": { ... } }` (the `name` gates matching) |
| v2 | `providerState` | a single string |

Only the **first** declared provider state on an interaction gates matching (a
scenario holds one active state at a time). The state is activated here with
`PUT /mockserver/scenario/pact-provider-state` and a `{"state": "..."}` body.

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./import_pact_with_provider_state.sh
```

## Expected output

1. The import returns `201 Created` with the generated expectation as JSON
   (its `id` is the interaction description, and it carries the
   `pact-provider-state` scenario gate).
2. Before activation, `GET /api/users/1` returns **404** — the gated
   interaction does not match yet.
3. Activating the scenario returns `200` with
   `{ "scenarioName": "pact-provider-state", "currentState": "a user with id 1 exists" }`.
4. After activation, `GET /api/users/1` returns **200** with the mocked body
   `{"id":1,"name":"Alice"}`.
