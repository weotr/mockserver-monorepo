#!/usr/bin/env bash
#
# Start MockServer, wait for it to be ready, then run a k6 scenario against it.
#
# Usage: runAll.sh [host] [script]
#   host    MockServer host (default localhost)
#   script  k6 script (default load.js)
#
set -euo pipefail

host="${1:-localhost}"
script="${2:-load.js}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${DIR}/runMockServer.sh"

echo "waiting for MockServer to be ready..."
for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null -X PUT "http://localhost:1080/mockserver/status"; then
    echo "MockServer is up"
    break
  fi
  sleep 1
done

"${DIR}/runK6.sh" "${host}" "${script}"
