#!/usr/bin/env bash
# Create an expectation that matches exactly twice then expires.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponse": {
    "body": "some_response_body"
  },
  "times": {
    "remainingTimes": 2,
    "unlimited": false
  },
  "timeToLive": {
    "unlimited": true
  }
}'
