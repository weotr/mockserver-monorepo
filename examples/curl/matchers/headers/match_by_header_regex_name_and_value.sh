#!/usr/bin/env bash
# Match requests with a header name starting with "Accept" and value containing "gzip".
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "headers": {
      "Accept.*": [
        ".*gzip.*"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
