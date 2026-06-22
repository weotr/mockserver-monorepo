#!/usr/bin/env bash
# Match a request body by fuzzy (approximate) string similarity.
# The FUZZY body matcher accepts a request whose body is similar enough to the
# "fuzzy" value, where "threshold" is the minimum similarity ratio (0.0-1.0,
# default 0.8) and "ignoreCase" controls case sensitivity (default false).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "POST",
    "path": "/some/path",
    "body": {
      "type": "FUZZY",
      "fuzzy": "the quick brown fox jumps over the lazy dog",
      "threshold": 0.8,
      "ignoreCase": true
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
