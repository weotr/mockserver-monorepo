#!/usr/bin/env bash
# Return a response with connection options to suppress Content-Length and Connection headers.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponse": {
    "body": "some_response_body",
    "connectionOptions": {
      "suppressContentLengthHeader": true,
      "suppressConnectionHeader": true
    }
  }
}'
