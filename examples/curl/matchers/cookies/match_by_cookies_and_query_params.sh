#!/usr/bin/env bash
# Match requests by cookies and query string parameters.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "GET",
    "path": "/view/cart",
    "queryStringParameters": {
      "cartId": [
        "055CA455-1DF7-45BB-8535-4F83E7266092"
      ]
    },
    "cookies": {
      "session": "4930456C-C718-476F-971F-CB8E047AB349"
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
