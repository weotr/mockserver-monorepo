#!/usr/bin/env bash
#
# Start a MockServer container for the performance suite. Metrics are enabled so
# Prometheus/Grafana (and the k6 soak test) can observe the server.
#
# Override the image with MOCKSERVER_IMAGE (defaults to the local snapshot build).
#
set -euo pipefail

IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"

docker rm -f mockserver >/dev/null 2>&1 || true
docker run \
  --env MOCKSERVER_LOG_LEVEL="${MOCKSERVER_LOG_LEVEL:-ERROR}" \
  --env MOCKSERVER_DISABLE_SYSTEM_OUT=true \
  --env MOCKSERVER_METRICS_ENABLED=true \
  -d --rm --name mockserver -p 1080:1080 "${IMAGE}" -serverPort 1080
