#!/usr/bin/env bash
# Retrieve recorded log messages filtered by request matcher.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/retrieve?type=LOGS" \
-d '{
    "path": "/some/path",
    "method": "POST"
}'
