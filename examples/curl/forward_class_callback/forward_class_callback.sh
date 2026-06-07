#!/usr/bin/env bash
# Forward requests using a Java class callback. The callback class must
# implement ExpectationForwardCallback and be on the MockServer classpath.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/api/proxy"
  },
  "httpForwardClassCallback": {
    "callbackClass": "org.mockserver.examples.mockserver.ForwardCallbackExample$TestForwardCallback"
  }
}'
