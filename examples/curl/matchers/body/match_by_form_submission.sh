#!/usr/bin/env bash
# Match POST requests with form-urlencoded body parameters.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "headers": {
      "Content-Type": [
        "application/x-www-form-urlencoded"
      ]
    },
    "body": {
      "type": "PARAMETERS",
      "parameters": {
        "email": [
          "joe.blogs@gmail.com"
        ],
        "password": [
          "secure_Password123"
        ]
      }
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
