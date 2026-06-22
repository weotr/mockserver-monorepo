# Conditional Request Matcher (curl)

## What it demonstrates

Matching with a conditional (if/then/else) request definition. The
`conditionalRequestDefinition` is itself a request matcher, so it is supplied
directly as the value of `httpRequest`: when the `if` matcher matches, the
`then` matcher must also match; otherwise the `else` matcher applies.

| Script | Description |
|--------|-------------|
| `conditional_request.sh` | GET requests must target `/admin`; all other methods `/public` |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./conditional_request.sh
```

## Expected output

The script prints the created expectation as JSON ending with
`"times" : { "unlimited" : true }`.
