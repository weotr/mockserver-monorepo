#!/usr/bin/env bash
# Retrieve all recorded request-response pairs.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/retrieve?type=REQUEST_RESPONSES"
