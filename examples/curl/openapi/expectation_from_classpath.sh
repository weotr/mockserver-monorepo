#!/usr/bin/env bash
# Create an expectation using an OpenAPI spec loaded from the classpath.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "specUrlOrPayload": "org/mockserver/openapi/openapi_petstore_example.json"
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
