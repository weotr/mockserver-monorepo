#!/usr/bin/env bash
# Match requests using MATCHING_KEY key match style for query parameters.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path",
    "queryStringParameters": {
      "keyMatchStyle": "MATCHING_KEY",
      "multiValuedParameter": [
        {
          "schema": {
            "type": "string",
            "pattern": "^[A-Z0-9-]+$"
          }
        }
      ],
      "maxItemCount": [
        {
          "schema": {
            "type": "integer"
          }
        }
      ]
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
