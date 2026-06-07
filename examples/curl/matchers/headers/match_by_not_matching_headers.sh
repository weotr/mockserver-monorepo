#!/usr/bin/env bash
# Match requests that do NOT have Accept or Accept-Encoding headers.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "headers": {
      "!Accept": [
        ".*"
      ],
      "!Accept-Encoding": [
        ".*"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
