#!/usr/bin/env bash
# Create an expectation with a class callback action.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some.*"
  },
  "httpClassCallback": {
    "callbackClass": "org.mockserver.examples.mockserver.CallbackActionExamples$TestExpectationCallback"
  }
}'
