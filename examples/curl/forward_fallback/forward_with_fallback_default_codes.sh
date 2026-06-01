#!/usr/bin/env bash
# Forward requests with a fallback response using default status code matching
# (500-599). Only httpForward and fallbackResponse are required.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/api/upstream"
  },
  "httpForwardWithFallback": {
    "httpForward": {
      "scheme": "HTTP",
      "host": "upstream.local",
      "port": 8080
    },
    "fallbackResponse": {
      "statusCode": 200,
      "body": "{\"fallback\": true}"
    }
  }
}'
