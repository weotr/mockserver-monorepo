#!/usr/bin/env bash
# Match requests by body matching a JsonPath expression.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "JSON_PATH",
      "jsonPath": "$.store.book[?(@.price < 10)]"
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
