#!/usr/bin/env bash
# Match a GraphQL request by its query using a GraphQLBody (type "GRAPHQL")
# request matcher, then return a fixed JSON response.
# The matcher compares the parsed GraphQL query AST, so formatting and field
# ordering differences in the incoming request do not affect matching.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/graphql",
    "body": {
      "type": "GRAPHQL",
      "query": "query GetUser($id: ID!) { user(id: $id) { name email } }"
    }
  },
  "httpResponse": {
    "headers": {
      "Content-Type": [
        "application/json"
      ]
    },
    "body": "{\"data\":{\"user\":{\"name\":\"Alice\",\"email\":\"alice@example.com\"}}}"
  }
}'
