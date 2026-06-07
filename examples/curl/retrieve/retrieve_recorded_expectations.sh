#!/usr/bin/env bash
# Retrieve all recorded expectations (from proxy mode).
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/retrieve?type=RECORDED_EXPECTATIONS"
