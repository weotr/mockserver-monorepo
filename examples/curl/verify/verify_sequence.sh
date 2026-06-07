#!/usr/bin/env bash
# Verify a sequence of requests was received in the specified order.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/verifySequence" \
-d '[
  {
    "path": "/some/path/one"
  },
  {
    "path": "/some/path/two"
  },
  {
    "path": "/some/path/three"
  }
]'
