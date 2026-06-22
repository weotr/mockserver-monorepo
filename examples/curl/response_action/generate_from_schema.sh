#!/usr/bin/env bash
# Generate the response body from an inline JSON Schema.
# When "generateFromSchema" is set (and no explicit body is provided),
# MockServer synthesises a schema-valid JSON body at response time. The value
# is a plain JSON Schema object encoded as a string, not a full OpenAPI document.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "GET",
    "path": "/some/path"
  },
  "httpResponse": {
    "statusCode": 200,
    "headers": {
      "Content-Type": [
        "application/json"
      ]
    },
    "generateFromSchema": "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"}},\"required\":[\"id\",\"name\"]}"
  }
}'
