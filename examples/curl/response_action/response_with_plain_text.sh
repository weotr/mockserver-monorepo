#!/usr/bin/env bash
# Return a response with plain text Content-Type header.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpResponse": {
    "headers": {
      "Content-Type": [
        "plain/text"
      ]
    },
    "body": "some_response_body"
  }
}'
