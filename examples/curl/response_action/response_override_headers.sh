#!/usr/bin/env bash
# Return a response with connection options to override Content-Length and keep-alive.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponse": {
    "body": "some_response_body",
    "connectionOptions": {
      "contentLengthHeaderOverride": 10,
      "keepAliveOverride": false
    }
  }
}'
