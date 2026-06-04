#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function integration_test() {
  trap 'tear-down 2>/dev/null || true' EXIT
  start-up
  TEST_EXIT_CODE=0
  wait_ready "mockserver" || return 1

  # Verify the container started and responds to status endpoint (PUT method)
  STATUS_RESPONSE=$(docker-exec-client "curl -v -s -o /dev/null -w '%{http_code}' -X PUT 'http://mockserver:1080/mockserver/status'") || TEST_EXIT_CODE=1
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    if [[ "${STATUS_RESPONSE}" != "200" ]]; then
      printFailureMessage "MockServer status endpoint returned unexpected HTTP status: \"${STATUS_RESPONSE}\" (expected 200)"
      TEST_EXIT_CODE=1
    fi
  fi

  # Verify JAVA_TOOL_OPTIONS was picked up by checking the JVM heap is limited
  # The JVM logs JAVA_TOOL_OPTIONS to stderr on startup; verify via docker logs
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    CONTAINER_ID=$(docker-compose -p "${TEST_CASE}" ps -q mockserver)
    JVM_LOG=$(docker logs "${CONTAINER_ID}" 2>&1 | head -5)
    if ! echo "${JVM_LOG}" | grep -q "Xmx256m"; then
      printFailureMessage "JAVA_TOOL_OPTIONS not found in container startup logs. Expected '-Xmx256m' in: \"${JVM_LOG}\""
      TEST_EXIT_CODE=1
    fi
  fi

  # Verify the server is actually functional by creating and matching an expectation
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/mockserver/expectation' -d \\\"{
                          'httpRequest' : {
                            'path' : '/some/path'
                          },
                          'httpResponse' : {
                            'body' : 'some_response_body'
                          }
                        }\\\"" || TEST_EXIT_CODE=1
  fi
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/some/path'")
    if [[ "${RESPONSE_BODY}" != "some_response_body" ]]; then
      printFailureMessage "Failed to retrieve response body for expectation matched by path, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  # tear-down handled by EXIT trap above.
  return ${TEST_EXIT_CODE}
}

integration_test
