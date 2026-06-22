# OpenAPI Contract Testing (JSON)

## What it demonstrates

The JSON request body for `PUT /mockserver/contractTest` — running an OpenAPI
specification as a **contract test against a live service**. MockServer
exercises each operation in the spec against `baseUrl` and validates that the
real responses conform to the spec.

| File | Description |
|------|-------------|
| `contract_test.json` | Contract-test request: spec URL + base URL + a single `operationId` |

The request body fields are:

| Field | Required | Description |
|-------|----------|-------------|
| `spec` (alias `specUrlOrPayload`) | yes | OpenAPI v3 spec as a URL, file path, or inline JSON/YAML |
| `baseUrl` | yes | Base URL of the live service under test (must include a host) |
| `operationId` | no | Restrict the run to a single operation; omit to test every operation |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- **A reachable target service** at `baseUrl` — contract testing makes real HTTP
  calls, so the service must be running and routable from MockServer.
- The target host is subject to MockServer's **SSRF policy**
  (`forwardProxyBlockPrivateNetworks`): a private, loopback, or cloud-metadata
  address is rejected with **403** unless that policy is disabled.
- `curl` installed (to send the payload)

## Run

```bash
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/contractTest" \
  -H "Content-Type: application/json" \
  -d @contract_test.json
```

## Expected output

A JSON report (HTTP `200`) with `baseUrl`, `totalOperations`, `passed`,
`failed`, `allPassed`, and a `results` array — one entry per operation with
`operationId`, `method`, `path`, `statusCodeReceived`, `passed`, and
`validationErrors`. `allPassed` is `true` only when every operation conformed
to the spec. A non-allowed `baseUrl` returns **403**; a missing `spec` or
`baseUrl` returns **400**.
