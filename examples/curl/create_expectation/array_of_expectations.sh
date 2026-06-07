#!/usr/bin/env bash
# Create multiple expectations in a single request using a JSON array.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '[
  {
    "httpRequest": {
      "path": "/somePathOne"
    },
    "httpResponse": {
      "statusCode": 200,
      "body": {
        "value": "one"
      }
    }
  },
  {
    "httpRequest": {
      "path": "/somePathTwo"
    },
    "httpResponse": {
      "statusCode": 200,
      "body": {
        "value": "two"
      }
    }
  },
  {
    "httpRequest": {
      "path": "/somePathThree"
    },
    "httpResponse": {
      "statusCode": 200,
      "body": {
        "value": "three"
      }
    }
  }
]'
