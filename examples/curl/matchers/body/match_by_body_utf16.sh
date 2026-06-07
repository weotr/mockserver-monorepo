#!/usr/bin/env bash
# Match requests by body with UTF-16 content type.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "STRING",
      "string": "some_utf16_string",
      "contentType": "text/plain; charset=utf-16"
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
