#!/usr/bin/env bash
# Switch responses based on hit count.
# With "responseMode":"SWITCH", MockServer serves the first entry of
# "httpResponses" for the first "switchAfter" matches, then advances to the
# next entry. Here the first 3 requests get 200, every request after that gets
# a 503 - useful for simulating a service that degrades over time.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponses": [
    {
      "statusCode": 200,
      "body": "healthy"
    },
    {
      "statusCode": 503,
      "body": "service unavailable"
    }
  ],
  "responseMode": "SWITCH",
  "switchAfter": 3
}'
