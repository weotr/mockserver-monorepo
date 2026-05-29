#!/usr/bin/env bash
#
# Run a k6 scenario against MockServer. Uses a local `k6` binary if present,
# otherwise falls back to the grafana/k6-based docker image
# (mockserver/mockserver:performance).
#
# Usage: runK6.sh [host] [script] [extra k6 args...]
#   host    MockServer host (default localhost; use host.docker.internal for
#           Docker Desktop when running k6 in a container)
#   script  k6 script under k6/ (default load.js; e.g. smoke.js, stress.js, soak.js)
#
set -euo pipefail

host="${1:-localhost}"
script="${2:-load.js}"
shift "$(( $# < 2 ? $# : 2 ))"  # remaining args pass through to k6

PERF_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K6_DIR="${PERF_TEST_DIR}/k6"

if [ ! -f "${K6_DIR}/${script}" ]; then
  echo "error: k6 script not found: ${K6_DIR}/${script}" >&2
  exit 1
fi

if command -v k6 >/dev/null 2>&1; then
  exec k6 run -e MOCKSERVER_HOST="${host}:1080" "$@" "${K6_DIR}/${script}"
fi

echo "local k6 not found — running via docker (mockserver/mockserver:performance)"
IMAGE="${PERF_IMAGE:-mockserver/mockserver:performance}"
docker rm -f k6 >/dev/null 2>&1 || true
exec docker run --rm --name k6 \
  --add-host host.docker.internal:host-gateway \
  --volume "${K6_DIR}:/k6:ro" \
  --env MOCKSERVER_HOST="${host}:1080" \
  "${IMAGE}" run "$@" "/k6/${script}"
