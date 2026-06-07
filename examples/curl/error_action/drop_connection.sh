#!/usr/bin/env bash
# Simulate an error by dropping the connection.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpError": {
    "dropConnection": true
  }
}'
