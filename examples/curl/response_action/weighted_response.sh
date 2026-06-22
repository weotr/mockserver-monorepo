#!/usr/bin/env bash
# Return one of several responses chosen at random by weight.
# With "responseMode":"WEIGHTED", MockServer picks from the "httpResponses"
# array using the matching index in "responseWeights" as the relative weight.
# Here 90% of matching requests get 200 and 10% get a 500 - useful for
# probabilistic failure injection.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponses": [
    {
      "statusCode": 200,
      "body": "success"
    },
    {
      "statusCode": 500,
      "body": "internal server error"
    }
  ],
  "responseMode": "WEIGHTED",
  "responseWeights": [
    90,
    10
  ]
}'
