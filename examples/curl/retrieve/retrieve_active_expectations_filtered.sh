#!/usr/bin/env bash
# Retrieve active expectations filtered by request matcher.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/retrieve?type=ACTIVE_EXPECTATIONS&format=JSON" \
-d '{
    "path": "/some/path",
    "method": "POST"
}'
