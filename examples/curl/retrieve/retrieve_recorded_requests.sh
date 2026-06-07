#!/usr/bin/env bash
# Retrieve all recorded requests (optionally filtered by request matcher).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/retrieve?type=REQUESTS&format=JSON" \
-d '{
    "path": "/some/path",
    "method": "POST"
}'
