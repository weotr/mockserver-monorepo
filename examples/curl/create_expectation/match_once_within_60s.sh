#!/usr/bin/env bash
# Create an expectation that matches once and expires after 60 seconds.
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
    "remainingTimes": 1,
    "unlimited": false
  },
  "timeToLive": {
    "timeUnit": "SECONDS",
    "timeToLive": 60,
    "unlimited": false
  }
}'
