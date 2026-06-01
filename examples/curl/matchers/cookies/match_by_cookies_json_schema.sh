#!/usr/bin/env bash
# Match requests by cookies and query parameters validated with JSON Schema.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "GET",
    "path": "/view/cart",
    "queryStringParameters": {
      "cartId": [
        {
          "schema": {
            "type": "string",
            "format": "uuid"
          }
        }
      ]
    },
    "cookies": {
      "session": {
        "schema": {
          "type": "string",
          "format": "uuid"
        }
      }
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
