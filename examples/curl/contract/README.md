# OpenAPI Contract Testing (curl)

## What it demonstrates

Running an OpenAPI specification as a **contract test against a live service**
via `PUT /mockserver/contractTest`. MockServer exercises each operation in the
spec against the supplied `baseUrl` and validates that the real responses
conform to the spec, returning a per-operation pass/fail report. This is the
inverse of the mocking endpoints: instead of MockServer impersonating the
service, it *checks* a running service against its contract.

| Script | Description |
|--------|-------------|
| `contract_test.sh` | Contract-test one operation (`listPets`) of the petstore spec against a base URL |

The request body fields are:

| Field | Required | Description |
|-------|----------|-------------|
| `spec` (alias `specUrlOrPayload`) | yes | OpenAPI v3 spec as a URL, file path, or inline JSON/YAML |
| `baseUrl` | yes | Base URL of the live service under test (must include a host, e.g. `https://api.example.com`) |
| `operationId` | no | Restrict the run to a single operation; omit to test every operation |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- **A reachable target service** at `baseUrl` — contract testing makes real HTTP
  calls to that service, so it must be running and routable from MockServer.
- The target host is subject to MockServer's **SSRF policy**
  (`forwardProxyBlockPrivateNetworks`): a private, loopback, or cloud-metadata
  address is rejected with **403** unless that policy is disabled. Point
  `baseUrl` at a permitted host (edit the script before running).
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./contract_test.sh
```

## Expected output

A JSON report (HTTP `200`) summarising the run and listing each operation:

```json
{
  "baseUrl" : "https://example.com",
  "totalOperations" : 1,
  "passed" : 0,
  "failed" : 1,
  "allPassed" : false,
  "results" : [ {
    "operationId" : "listPets",
    "method" : "GET",
    "path" : "/pets",
    "statusCodeReceived" : 404,
    "passed" : false,
    "validationErrors" : [ "..." ]
  } ]
}
```

`allPassed` is `true` only when every operation's response conformed to the
spec. A non-allowed `baseUrl` returns **403** with an `{"error":"..."}` body;
a missing/blank `spec` or `baseUrl` returns **400**.
