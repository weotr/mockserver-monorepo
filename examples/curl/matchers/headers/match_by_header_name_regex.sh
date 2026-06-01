#!/usr/bin/env bash
# Match requests that have any header starting with the name "Accept".
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "headers": {
      "Accept.*": []
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
