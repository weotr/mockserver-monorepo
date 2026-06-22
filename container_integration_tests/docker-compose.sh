#!/usr/bin/env bash

set -euo pipefail

INTEGRATION_TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
EXAMPLES_DIR="${INTEGRATION_TESTS_DIR}/../examples/docker-compose"

# Overlays use the `!reset` / `!override` YAML merge tags introduced in Docker
# Compose v2.22 (August 2023). Without these, host port-publishing from the
# base example would leak into CI and cause port clashes between tests, and
# volume overrides would append instead of replacing.
function check-compose-version() {
  local raw
  raw="$(docker-compose version --short 2>/dev/null || true)"
  if [[ -z "${raw}" ]]; then
    echo "docker-compose not found in PATH" >&2
    return 1
  fi
  local v="${raw#v}"
  local major="${v%%.*}"
  local rest="${v#*.}"
  local minor="${rest%%.*}"
  if [[ "${major}" -lt 2 ]] || { [[ "${major}" -eq 2 ]] && [[ "${minor}" -lt 22 ]]; }; then
    echo "docker-compose >= 2.22 required for !reset/!override merge tags; found ${raw}" >&2
    return 1
  fi
}
check-compose-version

# Build the -f arguments for a test case: the canonical example compose
# file is the base; if an overlay (docker-compose.override.yml) exists in
# the test directory it is layered on top. Falls back to a single in-place
# docker-compose.yml in the test directory for any test that has not yet
# been migrated to the overlay pattern.
function compose-files() {
  local case="${1}"
  local base="${EXAMPLES_DIR}/${case}/docker-compose.yml"
  local overlay="${INTEGRATION_TESTS_DIR}/${case}/docker-compose.override.yml"
  local legacy="${INTEGRATION_TESTS_DIR}/${case}/docker-compose.yml"

  if [[ -f "${base}" && -f "${overlay}" ]]; then
    echo "-f ${base} -f ${overlay}"
  elif [[ -f "${legacy}" ]]; then
    echo "-f ${legacy}"
  elif [[ -f "${base}" ]]; then
    echo "-f ${base}"
  else
    echo "no docker-compose.yml found for test case '${case}' (looked in ${base} and ${legacy})" >&2
    return 1
  fi
}

function docker-exec() {
  if [[ -z "${TEST_CASE:-}" ]]; then
    runCommand "docker-compose exec -T ${1} /bin/bash -c \"${2}\""
  else
    runCommand "docker-compose -p ${TEST_CASE} exec -T ${1} /bin/bash -c \"${2}\""
  fi
}

function docker-exec-client() {
  docker-exec "client" "${1}"
}

function tear-down() {
  local files
  files="$(compose-files "${TEST_CASE}")"
  export OVERRIDE_DIR="${INTEGRATION_TESTS_DIR}/${TEST_CASE}"
  runCommand "docker-compose ${files} -p ${TEST_CASE} down --remove-orphans || true"
}

function start-up() {
  local files
  files="$(compose-files "${TEST_CASE}")"
  # Exported so overlay files can reference the test directory in volume
  # paths via ${OVERRIDE_DIR} — relative paths in an overlay otherwise
  # resolve against the project directory (the base example's directory).
  export OVERRIDE_DIR="${INTEGRATION_TESTS_DIR}/${TEST_CASE}"
  runCommand "docker-compose ${files} -p ${TEST_CASE} up --build -d"
}

function container-logs() {
  printMessage "mockserver logs"
  # Scope to the test's compose project (otherwise the crashed project's logs
  # are not found) and never fail — a missing/crashed project must not abort
  # the caller under `set -e`.
  docker-compose -p "${TEST_CASE}" logs || true
}

# Poll a MockServer instance (by docker-compose service name) via the client
# container until the status endpoint responds, or fail after ~30 s.
# Usage: wait_ready <host> [port]  (port defaults to 1080)
function wait_ready() {
  local host="${1}" port="${2:-1080}"
  for _ in $(seq 1 30); do
    if docker-exec-client "curl -sf -o /dev/null -X PUT http://${host}:${port}/mockserver/status"; then
      return 0
    fi
    sleep 1
  done
  printMessage "FAIL: ${host}:${port} did not become ready"
  container-logs || true
  return 1
}

function clean-up-docker-containers() {
  runCommand "docker ps --all | grep mockserver/mockserver:integration_testing | awk '{ print \$1 }' | xargs docker stop"
  runCommand "docker ps --all | grep mockserver/mockserver:integration_testing | awk '{ print \$1 }' | xargs docker rm"
}
