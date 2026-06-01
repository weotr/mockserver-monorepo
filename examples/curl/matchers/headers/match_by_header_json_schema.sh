#!/usr/bin/env bash
# Match requests by header values validated with JSON Schema.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "headers": {
      "Accept.*": [
        {
          "schema": {
            "type": "string",
            "pattern": "^.*gzip.*$"
          }
        }
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
