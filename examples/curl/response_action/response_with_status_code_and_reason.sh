#!/usr/bin/env bash
# Return a response with a custom status code and reason phrase.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/some/path"
  },
  "httpResponse": {
    "statusCode": 418,
    "reasonPhrase": "I'\''m a teapot"
  }
}'
