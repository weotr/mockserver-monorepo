#!/usr/bin/env bash
# Match requests with optional query parameters (prefixed with ?).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "queryStringParameters": {
      "?cartId": [
        "[A-Z0-9\\-]+"
      ],
      "?maxItemCount": [
        {
          "schema": {
            "type": "integer"
          }
        }
      ],
      "?userId": [
        {
          "schema": {
            "type": "string",
            "format": "uuid"
          }
        }
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
