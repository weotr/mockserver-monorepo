#!/usr/bin/env bash
# Clear expectations and logs matching an OpenAPI operation.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/clear" \
-d '{
    "specUrlOrPayload": "https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json",
    "operationId": "showPetById"
}'
