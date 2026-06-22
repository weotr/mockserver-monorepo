#!/usr/bin/env bash
# shellcheck disable=SC2155

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
TEST_CASE="${TEST_CASE:-SCRIPT_DIR}"
source "${SCRIPT_DIR}/../helm-deploy.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function integration_test() {
  # Pass the initialiser JSON via a --values file rather than inline --set-string:
  # helm's --set parser splits the expectation JSON on its commas/braces, producing
  # "key ... has no value". A values file (the chart's documented form) sets the
  # string verbatim. See helm/mockserver/values.yaml app.config.* for the schema.
  local VALUES_FILE
  VALUES_FILE="$(mktemp)"
  cat > "${VALUES_FILE}" <<'YAML'
app:
  config:
    enabled: true
    properties: |
      mockserver.initializationJsonPath=/config/initializerJson.json
    initializerJson: |
      [{"httpRequest":{"path":"/preset"},"httpResponse":{"body":"preset_response"}}]
YAML
  start-up "--set image.repositoryNameAndTag=mockserver/mockserver:integration_testing --values ${VALUES_FILE}"
  TEST_EXIT_CODE=0
  sleep 3
  run-helm-test || TEST_EXIT_CODE=1
  RESPONSE_BODY=$(runCommand "curl -v -s -X PUT 'http://${MOCKSERVER_HOST}/preset'") || TEST_EXIT_CODE=1
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    if [[ "${RESPONSE_BODY}" != "preset_response" ]]; then
      printFailureMessage "Failed to retrieve response body for pre-loaded expectation, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi
  rm -f "${VALUES_FILE}"
  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  tear-down
  return ${TEST_EXIT_CODE}
}

integration_test
