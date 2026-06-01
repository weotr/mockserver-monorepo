#!/usr/bin/env bash
# Match requests by JSON body with STRICT matching (exact match).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "JSON",
      "json": {
        "id": 1,
        "name": "A green door",
        "price": 12.50,
        "tags": [
          "home",
          "green"
        ]
      },
      "matchType": "STRICT"
    }
  },
  "httpResponse": {
    "statusCode": 202,
    "body": "some_response_body"
  }
}'
