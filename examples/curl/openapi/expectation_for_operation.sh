#!/usr/bin/env bash
# Create an expectation for a specific OpenAPI operation with a custom response.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "specUrlOrPayload": "https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json",
    "operationId": "showPetById"
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
