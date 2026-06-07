#!/usr/bin/env bash
# Match requests by JSON body with json-unit placeholders for flexible matching.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "JSON",
      "json": {
        "id": 1,
        "name": "A green door",
        "price": "${json-unit.ignore-element}",
        "enabled": "${json-unit.any-boolean}",
        "tags": [
          "home",
          "green"
        ]
      }
    }
  },
  "httpResponse": {
    "statusCode": 202,
    "body": "some_response_body"
  }
}'
