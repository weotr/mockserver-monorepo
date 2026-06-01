#!/usr/bin/env bash
# Verify requests matching an OpenAPI spec operation were received.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/verify" \
-d '{
  "httpRequest": {
    "specUrlOrPayload": "https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json",
    "operationId": "showPetById"
  },
  "times": {
    "atLeast": 2,
    "atMost": 2
  }
}'
