#!/usr/bin/env bash
# Return a gRPC error (UNAVAILABLE) and close the connection.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/grpc/service/FailingMethod"
  },
  "grpcStreamResponse": {
    "statusName": "UNAVAILABLE",
    "statusMessage": "service is temporarily unavailable",
    "closeConnection": true
  }
}'
