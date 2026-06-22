#!/usr/bin/env bash
# Run an OpenAPI spec as a contract test against a live service.
# Each operation in the spec is exercised against "baseUrl" and the actual
# response is validated against the spec; MockServer returns a pass/fail report.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).
#
# NOTE: "baseUrl" must point at a REACHABLE service under test. The base URL is
# subject to the same SSRF policy as forwarding/replay — a private, loopback or
# metadata address is rejected with 403 unless forwardProxyBlockPrivateNetworks
# is disabled. Replace the example baseUrl with your own service.

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/contractTest" \
-H "Content-Type: application/json" \
-d '{
  "spec": "https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json",
  "baseUrl": "https://example.com",
  "operationId": "listPets"
}'
