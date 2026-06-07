#!/usr/bin/env bash
# Verify a request was received exactly twice.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/verify" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "times": {
    "atLeast": 2,
    "atMost": 2
  }
}'
