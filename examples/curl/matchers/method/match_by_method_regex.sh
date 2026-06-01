#!/usr/bin/env bash
# Match requests by method regex (e.g. POST, PUT, PATCH).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "P.*{2,3}"
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
