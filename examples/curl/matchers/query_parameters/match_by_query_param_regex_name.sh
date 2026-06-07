#!/usr/bin/env bash
# Match requests by query parameter with a regex name.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "queryStringParameters": {
      "[A-z]{0,10}": [
        "055CA455-1DF7-45BB-8535-4F83E7266092"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
