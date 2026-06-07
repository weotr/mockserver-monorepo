#!/usr/bin/env bash
# Reset MockServer — clears all expectations, logs, and recorded requests.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/reset"
