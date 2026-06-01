#!/usr/bin/env bash
# Verify a request was received at most twice.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/verify" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "times": {
    "atMost": 2
  }
}'
