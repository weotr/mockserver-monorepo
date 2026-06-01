#!/usr/bin/env bash
# Return a raw binary response. The binaryData field is base64-encoded;
# "SGVsbG8gV29ybGQh" decodes to "Hello World!".
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/download/file.bin"
  },
  "binaryResponse": {
    "binaryData": "SGVsbG8gV29ybGQh"
  }
}'
