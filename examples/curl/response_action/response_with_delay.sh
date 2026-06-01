#!/usr/bin/env bash
# Return a response after a 10-second delay.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponse": {
    "body": "some_response_body",
    "delay": {
      "timeUnit": "SECONDS",
      "value": 10
    }
  }
}'
