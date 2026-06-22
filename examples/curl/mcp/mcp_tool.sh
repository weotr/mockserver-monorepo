#!/usr/bin/env bash
# Mock an MCP (Model Context Protocol) server over HTTP+JSON-RPC.
# Two expectations are created:
#   1. tools/list  - advertise an available tool ("get_weather")
#   2. tools/call  - return a result when that tool is invoked
# The JSON-RPC id is echoed back to the client via a Velocity template
# ($!{request.jsonRpcRawId}). Requests are matched by JSON-RPC method using
# the JSON_RPC body matcher.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

# 1. Advertise the tool catalogue (responds to tools/list)
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/mcp",
    "body": {
      "type": "JSON_RPC",
      "method": "tools/list"
    }
  },
  "httpResponseTemplate": {
    "templateType": "VELOCITY",
    "template": "{\"statusCode\": 200, \"headers\": [{\"name\": \"Content-Type\", \"values\": [\"application/json\"]}], \"body\": {\"jsonrpc\": \"2.0\", \"result\": {\"tools\": [{\"name\": \"get_weather\", \"description\": \"Get the weather for a city\", \"inputSchema\": {\"type\": \"object\", \"properties\": {\"city\": {\"type\": \"string\"}}}}]}, \"id\": $!{request.jsonRpcRawId}}}"
  }
}'

# 2. Return a tool result when get_weather is called (responds to tools/call)
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/mcp",
    "body": {
      "type": "JSON_PATH",
      "jsonPath": "$[?(@.method == '\''tools/call'\'' && @.params.name == '\''get_weather'\'')]"
    }
  },
  "httpResponseTemplate": {
    "templateType": "VELOCITY",
    "template": "{\"statusCode\": 200, \"headers\": [{\"name\": \"Content-Type\", \"values\": [\"application/json\"]}], \"body\": {\"jsonrpc\": \"2.0\", \"result\": {\"content\": [{\"type\": \"text\", \"text\": \"72F and sunny\"}], \"isError\": false}, \"id\": $!{request.jsonRpcRawId}}}"
  }
}'
