# GraphQL Mocking (curl)

## What it demonstrates

Mocking GraphQL endpoints with raw REST calls, two ways:

| Script | Description |
|--------|-------------|
| `import_sdl.sh` | Import a GraphQL SDL via `PUT /mockserver/graphql`; MockServer auto-generates schema-valid example responses |
| `match_by_graphql_query.sh` | Match a request by a `GRAPHQL` body matcher (query AST) and return a fixed response |

The SDL import sends the raw schema as the request body
(`Content-Type: application/graphql`), not a JSON wrapper. The query matcher
compares the parsed GraphQL AST, so request formatting differences are ignored.

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./import_sdl.sh
./match_by_graphql_query.sh
```

## Expected output

`import_sdl.sh` returns `201 Created` with a JSON array of the auto-generated
expectations. `match_by_graphql_query.sh` prints the single created expectation
as JSON ending with `"times" : { "unlimited" : true }`.
