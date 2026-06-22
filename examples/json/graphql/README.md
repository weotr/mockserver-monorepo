# GraphQL Matcher Examples

JSON expectation payloads that match a GraphQL request by its query using a `GRAPHQL` body
matcher, then return a fixed JSON response.

| File | Description |
|------|-------------|
| `match_by_query.json` | Match a GraphQL request by its `query` string |
| `match_by_operation_and_variables_schema.json` | Match by `query` + `operationName` and validate `variables` against a JSON Schema |

## What it demonstrates

- A GraphQL request body matcher is a body with `"type": "GRAPHQL"` and a `query`.
  The query is compared structurally (whitespace-insensitive), so cosmetic formatting
  differences in the client's request still match.
- `operationName` narrows the match to a named operation.
- `variablesSchema` is a JSON Schema (as a string) applied to the request's `variables`
  object, so the expectation only matches when the variables are well-formed.
- The response is a normal `httpResponse` returning a GraphQL `{"data":{...}}` envelope.

## Prerequisites

- A running MockServer instance (default `http://localhost:1080`).
- A GraphQL client (or `curl`) that POSTs `{"query":...,"variables":...}` to `/graphql`.

## Run

```bash
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
  -d @examples/json/graphql/match_by_query.json
```

Then exercise the mock:

```bash
curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/graphql" \
  -H 'Content-Type: application/json' \
  -d '{"query":"query GetUser($id: ID!) { user(id: $id) { name email } }","variables":{"id":"1"}}'
```

## Expected output

- `match_by_query.json` returns `{"data":{"user":{"name":"Alice","email":"alice@example.com"}}}`.
- `match_by_operation_and_variables_schema.json` returns
  `{"data":{"createOrder":{"id":"order-42","status":"PENDING"}}}` only when the `CreateOrder`
  mutation is sent with `variables.input` containing `productId` and `quantity`.
