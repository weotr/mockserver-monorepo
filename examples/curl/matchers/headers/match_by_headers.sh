#!/usr/bin/env bash
# Match requests by specific header names and values.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "GET",
    "path": "/some/path",
    "headers": {
      "Accept": [
        "application/json"
      ],
      "Accept-Encoding": [
        "gzip, deflate, br"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
