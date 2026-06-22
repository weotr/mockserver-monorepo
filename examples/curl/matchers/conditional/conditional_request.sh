#!/usr/bin/env bash
# Match using a conditional (if/then/else) request definition.
# The conditionalRequestDefinition is itself a request matcher, so it is used
# directly as the value of "httpRequest". When the "if" matcher matches the
# request, the "then" matcher must also match; otherwise the "else" matcher is
# applied. Here: GET requests must target /admin, all other methods /public.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "if": {
      "method": "GET"
    },
    "then": {
      "path": "/admin"
    },
    "else": {
      "path": "/public"
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
