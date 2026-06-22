#!/usr/bin/env bash
# Run the mockserver-client-node browser integration tests (Playwright headless
# Chromium) against a MockServer Docker container with CORS enabled.
#
# Hard CI gate — a failure here blocks master.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_NAME="mockserver-browser-test-$$"
MOCKSERVER_NAME="mockserver-browser-test-server-$$"

cleanup() {
  docker rm -f "$MOCKSERVER_NAME" 2>/dev/null || true
  docker network rm "$NETWORK_NAME" 2>/dev/null || true
}
trap cleanup EXIT

docker network create "$NETWORK_NAME"

docker run -d \
  --name "$MOCKSERVER_NAME" \
  --network "$NETWORK_NAME" \
  -e "MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES=true" \
  -e 'MOCKSERVER_CORS_ALLOW_METHODS=CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE' \
  -e 'MOCKSERVER_CORS_ALLOW_HEADERS=Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization' \
  -e "MOCKSERVER_CORS_ALLOW_CREDENTIALS=true" \
  -e "MOCKSERVER_CORS_MAX_AGE_IN_SECONDS=300" \
  -e "MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION=false" \
  mockserver/mockserver:snapshot

# Wait for MockServer to be healthy via Docker HEALTHCHECK
echo "--- Waiting for MockServer to become healthy..."
DEADLINE=$((SECONDS + 120))
READY=false
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$MOCKSERVER_NAME" 2>/dev/null || echo "unknown")
  case "$STATUS" in
    healthy)
      echo "MockServer is healthy"
      READY=true
      break
      ;;
    unhealthy)
      echo "ERROR: MockServer container reported unhealthy"
      break
      ;;
  esac
  sleep 2
done

if [ "$READY" != "true" ]; then
  echo "ERROR: MockServer failed to become healthy within the deadline (status: ${STATUS})"
  echo "--- :docker: MockServer container logs"
  docker logs "$MOCKSERVER_NAME"
  exit 1
fi

# Run browser tests inside a Playwright Docker image (includes Chromium).
# The Playwright container needs access to the MockServer container, so join
# the same Docker network.  MOCKSERVER_HOST points at the container name.
#
# Derive the image tag from the @playwright/test version in the lockfile so the
# bundled Chromium always matches the installed Playwright. A hardcoded tag drifts
# the moment Dependabot bumps @playwright/test (a v1.60.0 image lacks the Chromium
# build a 1.61 client needs -> "browserType.launch: Executable doesn't exist",
# which reddened every mockserver-node build until this fix).
PW_VERSION=$(python3 -c "import json;print(json.load(open('mockserver-client-node/package-lock.json'))['packages']['node_modules/@playwright/test']['version'])")
echo "--- Using Playwright image v${PW_VERSION}-noble (from package-lock.json)"
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "mcr.microsoft.com/playwright:v${PW_VERSION}-noble" \
  -w /build/mockserver-client-node \
  --cache npm \
  -e "MOCKSERVER_HOST=$MOCKSERVER_NAME" \
  -e "MOCKSERVER_PORT=1080" \
  --network "$NETWORK_NAME" \
  -- bash -c 'npm ci && npx playwright test --config test/browser/playwright.config.js'
