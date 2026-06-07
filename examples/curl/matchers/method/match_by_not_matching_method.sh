#!/usr/bin/env bash
# Match requests by negated method (NOT GET).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "!GET"
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
