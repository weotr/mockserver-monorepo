#!/usr/bin/env bash
# shellcheck disable=SC2155

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
TEST_CASE="${TEST_CASE:-$(basename "${SCRIPT_DIR}")}"
source "${SCRIPT_DIR}/../helm-deploy.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function integration_test() {
  trap 'tear-down 2>/dev/null || true' EXIT
  # Deploy with config enabled, a properties file, and an initialiser JSON expectation.
  # Uses default release name + port to match the existing helm test pattern.
  # The initialiser JSON is passed via a --values file rather than inline --set-string:
  # helm's --set parser splits the expectation JSON on its commas/braces ("key ... has
  # no value"). A values file (the chart's documented form) sets the string verbatim.
  local VALUES_FILE
  VALUES_FILE="$(mktemp)"
  cat > "${VALUES_FILE}" <<'YAML'
app:
  config:
    enabled: true
    properties: |
      mockserver.initializationJsonPath=/config/initializerJson.json
    initializerJson: |
      [{"httpRequest":{"path":"/configmap-test"},"httpResponse":{"body":"configmap_injected_response"}}]
YAML
  start-up "--set image.repositoryNameAndTag=mockserver/mockserver:integration_testing --values ${VALUES_FILE}"
  TEST_EXIT_CODE=0
  sleep 3

  # Verify the helm test pod can reach MockServer
  run-helm-test || TEST_EXIT_CODE=1

  # Verify the status endpoint works (config was applied)
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    STATUS_CODE=$(runCommand "curl -v -s -o /dev/null -w '%{http_code}' -X PUT 'http://${MOCKSERVER_HOST}/mockserver/status'") || TEST_EXIT_CODE=1
    if [[ "${STATUS_CODE}" != "200" ]]; then
      printFailureMessage "MockServer status endpoint returned unexpected HTTP status: \"${STATUS_CODE}\" (expected 200)"
      TEST_EXIT_CODE=1
    fi
  fi

  # Verify the initialiser JSON expectation was loaded and responds correctly
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(runCommand "curl -v -s -X PUT 'http://${MOCKSERVER_HOST}/configmap-test'") || TEST_EXIT_CODE=1
    if [[ "${RESPONSE_BODY}" != "configmap_injected_response" ]]; then
      printFailureMessage "Failed to retrieve response body for configmap-injected expectation, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi

  rm -f "${VALUES_FILE}"
  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  # tear-down handled by EXIT trap above.
  return ${TEST_EXIT_CODE}
}

integration_test
