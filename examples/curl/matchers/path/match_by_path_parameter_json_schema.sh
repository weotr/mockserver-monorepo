#!/usr/bin/env bash
# Match requests by path parameters validated with JSON Schema.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path/{cartId}/{maxItemCount}",
    "pathParameters": {
      "cartId": [
        {
          "schema": {
            "type": "string",
            "pattern": "^[A-Z0-9-]+$"
          }
        }
      ],
      "maxItemCount": [
        {
          "schema": {
            "type": "integer"
          }
        }
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
