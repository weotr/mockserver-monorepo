#!/usr/bin/env bash
# Match requests that have an Accept header without "application/json"
# and an Accept-Encoding without "gzip".
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "headers": {
      "Accept": [
        "!application/json"
      ],
      "Accept-Encoding": [
        "!.*gzip.*"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
