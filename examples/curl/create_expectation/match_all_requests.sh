#!/usr/bin/env bash
# Create an expectation that matches ALL incoming requests (no request matcher).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpResponse": {
    "body": "some_response_body"
  }
}'
