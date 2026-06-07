#!/usr/bin/env bash
# Match requests by query parameter with a regex value.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "queryStringParameters": {
      "cartId": [
        "[A-Z0-9\\-]+"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
