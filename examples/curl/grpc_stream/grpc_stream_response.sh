#!/usr/bin/env bash
# Return a gRPC server-streaming response with two messages. The second
# message is delayed by 500 ms to simulate a real stream.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/grpc/service/StreamMethod"
  },
  "grpcStreamResponse": {
    "statusName": "OK",
    "statusMessage": "success",
    "messages": [
      {
        "json": "{\"id\": 1, \"value\": \"first\"}"
      },
      {
        "json": "{\"id\": 2, \"value\": \"second\"}",
        "delay": {
          "timeUnit": "MILLISECONDS",
          "value": 500
        }
      }
    ]
  }
}'
