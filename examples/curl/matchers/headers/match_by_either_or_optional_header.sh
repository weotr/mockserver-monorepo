#!/usr/bin/env bash
# Match requests with either/or optional headers.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "headers": {
      "headerOne|headerTwo": [
        ".*"
      ],
      "?headerOne": [
        "headerOneValue"
      ],
      "?headerTwo": [
        "headerTwoValue"
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
