#!/usr/bin/env bash
# Mock an A2A task response.
# A2A tasks are sent as JSON-RPC "tasks/send" calls to the agent's endpoint.
# This expectation matches that JSON-RPC method and returns a completed task
# with a text artifact, echoing the JSON-RPC id back via a Velocity template.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/a2a",
    "body": {
      "type": "JSON_RPC",
      "method": "tasks/send"
    }
  },
  "httpResponseTemplate": {
    "templateType": "VELOCITY",
    "template": "{\"statusCode\": 200, \"headers\": [{\"name\": \"Content-Type\", \"values\": [\"application/json\"]}], \"body\": {\"jsonrpc\": \"2.0\", \"result\": {\"id\": \"mock-task-id\", \"status\": {\"state\": \"completed\"}, \"artifacts\": [{\"parts\": [{\"type\": \"text\", \"text\": \"Bonjour\"}]}]}, \"id\": $!{request.jsonRpcRawId}}}"
  }
}'
