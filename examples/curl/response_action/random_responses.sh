#!/usr/bin/env bash
# Return a random response on each request.
# With "httpResponses" (plural) and "responseMode":"RANDOM", MockServer picks one
# of the listed responses at random for every matching request - useful for
# simulating an unpredictable / flaky service. (For a weighted random split use
# "responseMode":"WEIGHTED" with "responseWeights"; see weighted_response.sh.)
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpResponses": [
    { "statusCode": 200, "body": "success" },
    { "statusCode": 500, "body": "internal server error" },
    { "statusCode": 429, "body": "too many requests" }
  ],
  "responseMode": "RANDOM"
}'
