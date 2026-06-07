#!/usr/bin/env bash
# Forward requests to an upstream host; if upstream returns 500/502/503
# or times out, return a fallback mock response instead.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/api/backend"
  },
  "httpForwardWithFallback": {
    "httpForward": {
      "scheme": "HTTPS",
      "host": "backend.example.com",
      "port": 443
    },
    "fallbackResponse": {
      "statusCode": 503,
      "body": "service temporarily unavailable"
    },
    "fallbackOnStatusCodes": [500, 502, 503],
    "fallbackOnTimeout": true
  }
}'
