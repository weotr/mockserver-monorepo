#!/usr/bin/env bash
# Clear only request logs (not expectations) matching a request path.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/clear?type=log" \
-d '{
    "path": "/some/path"
}'
