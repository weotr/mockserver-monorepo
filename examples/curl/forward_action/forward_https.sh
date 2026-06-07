#!/usr/bin/env bash
# Forward matching requests to a target host over HTTPS.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpForward": {
    "host": "mock-server.com",
    "port": 443,
    "scheme": "HTTPS"
  }
}'
