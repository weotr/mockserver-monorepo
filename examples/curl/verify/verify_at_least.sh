#!/usr/bin/env bash
# Verify a request was received at least twice.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/verify" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "times": {
    "atLeast": 2
  }
}'
