#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function cleanup() {
  tear-down 2>/dev/null || true
}

# Wait for a MockServer instance (by docker-compose service name) to answer its
# status endpoint, or fail the test if it never comes up.
function wait_ready() {
  local host="${1}"
  for _ in $(seq 1 30); do
    if docker-exec-client "curl -sf -o /dev/null -X PUT http://${host}:1080/mockserver/status"; then
      return 0
    fi
    sleep 1
  done
  printMessage "FAIL: ${host} did not become ready"
  container-logs || true
  return 1
}

# Retry a client-side check until it passes or attempts run out — absorbs the
# Prometheus scrape interval and the OTLP export interval on a cold runner
# without a brittle fixed sleep. Sets TEST_EXIT_CODE=1 on exhaustion.
function assert_eventually() {
  local desc="${1}" check="${2}" attempts="${3:-12}"
  for _ in $(seq 1 "${attempts}"); do
    if docker-exec-client "${check}"; then
      printMessage "PASS: ${desc}"
      return 0
    fi
    sleep 2
  done
  printMessage "FAIL: ${desc}"
  TEST_EXIT_CODE=1
  return 1
}

function integration_test() {
  trap cleanup EXIT
  start-up
  TEST_EXIT_CODE=0

  # both the server under test and the MockServer acting as the OTLP sink
  wait_ready "mockserver" || return 1
  wait_ready "otlp-receiver" || return 1

  # Tell the OTLP receiver to accept the exporter's metric POSTs with 200 so the
  # export succeeds cleanly (an unmatched request would 404 and make the OTel SDK
  # log errors and back off). The request is still recorded either way.
  docker-exec-client "curl -sf -o /dev/null -X PUT 'http://otlp-receiver:1080/mockserver/expectation' -d \\\"{
                        'httpRequest' : { 'method' : 'POST', 'path' : '/v1/metrics' },
                        'httpResponse' : { 'statusCode' : 200 }
                      }\\\"" || true

  # Drive some traffic. requests_received_count increments on ANY request, so
  # no expectation is needed (the unmatched requests return 404 but still count).
  for _ in $(seq 1 5); do
    docker-exec-client "curl -s -o /dev/null http://mockserver:1080/anything" || true
  done

  # Assert each metric path, retrying to absorb the scrape + OTLP-export
  # intervals rather than relying on a single brittle sleep.

  # 1 — Prometheus scraped MockServer's request counter (the scrape path)
  assert_eventually "Prometheus scraped requests_received_count" \
    "curl -sf 'http://prometheus:9090/api/v1/query?query=requests_received_count' | jq -e '.data.result[0].value[1] | tonumber > 0' >/dev/null" || true

  # 2 — JVM gauges present on the scrape path (validates JvmMetricsCollector)
  assert_eventually "JVM metrics present in Prometheus" \
    "curl -sf 'http://prometheus:9090/api/v1/query?query=jvm_memory_used_bytes' | jq -e '.data.result | length > 0' >/dev/null" || true

  # 3 — MockServer actually pushed OTLP metric exports over the wire: the OTLP
  # receiver verifies it got at least one POST /v1/metrics (verify returns 202
  # on success, 406 otherwise, so curl -sf passes only when it has arrived).
  assert_eventually "MockServer exported metrics via OTLP (POST /v1/metrics received)" \
    "curl -sf -o /dev/null -X PUT 'http://otlp-receiver:1080/mockserver/verify' -d \\\"{
       'httpRequest' : { 'method' : 'POST', 'path' : '/v1/metrics' },
       'times' : { 'atLeast' : 1, 'atMost' : -1 }
     }\\\"" || true

  if [[ "${TEST_EXIT_CODE}" != "0" ]]; then
    container-logs || true
  fi
  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  return "${TEST_EXIT_CODE}"
}

integration_test
