#!/usr/bin/env bash
# Return a response with a Set-Cookie header.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpResponse": {
    "headers": {
      "Content-Type": [
        "plain/text"
      ]
    },
    "cookies": {
      "Session": "97d43b1e-fe03-4855-926a-f448eddac32f"
    },
    "body": "some_response_body"
  }
}'
