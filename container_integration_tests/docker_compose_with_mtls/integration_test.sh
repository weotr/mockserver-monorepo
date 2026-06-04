#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

EXAMPLE_DIR="${SCRIPT_DIR}/../../examples/docker-compose/docker_compose_with_mtls"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function prepare() {
  runCommand "rm -rf ${SCRIPT_DIR}/certs ${SCRIPT_DIR}/config"
  runCommand "mkdir -p ${SCRIPT_DIR}/certs ${SCRIPT_DIR}/config"
  # generate_certs.sh writes to its own directory; copy it into the test
  # certs dir first so generation lands there (not in the example dir).
  runCommand "cp ${EXAMPLE_DIR}/certs/generate_certs.sh ${SCRIPT_DIR}/certs/"
  runCommand "bash ${SCRIPT_DIR}/certs/generate_certs.sh openssl"
  runCommand "cp ${EXAMPLE_DIR}/config/expectationInitialiser.json ${SCRIPT_DIR}/config/"
  runCommand "chmod -R a+r ${SCRIPT_DIR}/certs ${SCRIPT_DIR}/config"
}

function integration_test() {
  prepare
  start-up
  TEST_EXIT_CODE=0
  # The server requires mTLS, so the standard wait_ready (plain HTTP) cannot
  # be used. Poll the status endpoint via the client container with certs.
  local mtls_ready="false"
  for _ in $(seq 1 30); do
    if docker-exec-client "curl -sf -o /dev/null --cacert /certs/ca.pem --cert /certs/client-cert.pem --key /certs/client-key-pkcs8.pem -X PUT https://mockserver:1080/mockserver/status"; then
      mtls_ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${mtls_ready}" != "true" ]]; then
    printFailureMessage "mTLS MockServer did not become ready within 30 s"
    container-logs || true
    logTestResult "1" "${TEST_CASE}"
    tear-down
    return 1
  fi

  # Successful mTLS request — should return the seeded body.
  RESPONSE_BODY=$(docker-exec-client "curl -s --cacert /certs/ca.pem --cert /certs/client-cert.pem --key /certs/client-key-pkcs8.pem https://mockserver:1080/hello") || TEST_EXIT_CODE=1
  if [[ "${RESPONSE_BODY}" != *"hello from mTLS MockServer"* ]]; then
    printFailureMessage "Expected mTLS hello response, got: \"${RESPONSE_BODY}\""
    TEST_EXIT_CODE=1
  fi

  # No-client-cert request — must FAIL (TLS handshake rejection). curl exits non-zero.
  if docker-exec-client "curl -sS --cacert /certs/ca.pem https://mockserver:1080/hello" >/dev/null 2>&1; then
    printFailureMessage "Request without client cert succeeded — mTLS is not enforced"
    TEST_EXIT_CODE=1
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  tear-down
  return ${TEST_EXIT_CODE}
}

integration_test
