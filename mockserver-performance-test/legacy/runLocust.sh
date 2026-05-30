#!/usr/bin/env bash
#
# LEGACY Locust runner — retired, kept one release for reference. The current
# performance suite is k6: see ../k6/ and ../scripts/runK6.sh.
#
# NOTE: docker_performance_test.sh uses curl, which the base locustio/locust
# image does not ship (the old mockserver/mockserver:performance image added it,
# but that tag is now the k6 image). Override LOCUST_IMAGE with a locust+curl
# image to run, or install curl in the container.
#
set -euo pipefail

host="${1:-localhost}"  # use host.docker.internal for Docker Desktop
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="${LOCUST_IMAGE:-locustio/locust:2.44.0}"

docker rm -f locust >/dev/null 2>&1 || true
docker run \
  --volume "${DIR}/docker_performance_test.sh:/docker_performance_test.sh" \
  --volume "${DIR}/locustfile.py:/locustfile.py" \
  --env MOCKSERVER_HOST="${host}:1080" \
  --rm --name locust -p 8089:8089 \
  --entrypoint /docker_performance_test.sh \
  "${IMAGE}"
