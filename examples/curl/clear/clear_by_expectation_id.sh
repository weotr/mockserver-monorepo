#!/usr/bin/env bash
# Clear a specific expectation by its ID.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/clear" \
-d '{
    "id": "31e4ca35-66c6-4645-afeb-6e66c4ca0559"
}'
