#!/usr/bin/env bash
# Match requests with an XML body matching an XPath expression.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "XPATH",
      "xpath": "/bookstore/book[price>30]/price"
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
