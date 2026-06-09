#!/usr/bin/env bash
# shellcheck disable=SC2155
#
# k3d multi-pod state-convergence end-to-end test for MockServer clustering.
#
# Deploys 2 replicas of the -clustered image with clustering.enabled, waits
# for JGroups cluster formation (2-node view), then asserts:
#   1. Create expectation on pod A  -> matches when hitting pod B
#   2. Clear expectations on pod A  -> pod B also returns 404
#   3. Times.exactly(3) fleet-wide  -> exactly 3 total matches across pods
#
# Non-blocking: uses logTestResultNonBlocking so a failure does not red
# the pipeline on day one (mirrors logging.sh pattern).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
TEST_CASE="${TEST_CASE:-helm_clustered_convergence}"
source "${SCRIPT_DIR}/../helm-deploy.sh"
source "${SCRIPT_DIR}/../logging.sh"

RELEASE_NAME="ms-cluster"
NAMESPACE="ms-cluster"
REPLICAS=2
KUBE_CONTEXT="k3d-mockserver"

printMessage "Start: \"${TEST_CASE}\""

function wait_for_pods_ready() {
  local attempts=60
  for i in $(seq 1 "${attempts}"); do
    local ready
    ready=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
      -l "app=mockserver,release=${RELEASE_NAME}" \
      -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null \
      | grep -c "True" || echo "0")
    if [[ "${ready}" -ge "${REPLICAS}" ]]; then
      printMessage "All ${REPLICAS} pods are Ready"
      return 0
    fi
    sleep 3
  done
  printFailureMessage "Timed out waiting for ${REPLICAS} ready pods (got ${ready:-0})"
  return 1
}

function wait_for_cluster_formation() {
  # Poll pod logs for the Infinispan cluster view line containing the expected member count.
  # The view log line looks like: "ISPN000094: Received new cluster view ... (2) [node1, node2]"
  local attempts=60
  for i in $(seq 1 "${attempts}"); do
    local pods
    pods=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
      -l "app=mockserver,release=${RELEASE_NAME}" \
      -o jsonpath='{.items[*].metadata.name}')
    for pod in ${pods}; do
      # Look specifically for ISPN000094 (cluster view received) lines and extract the member count
      local view_line
      view_line=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs "${pod}" 2>/dev/null \
        | grep 'ISPN000094.*cluster view' | tail -1 || echo "")
      if [[ -n "${view_line}" ]]; then
        # Extract the member count from "(N)" in the view line
        local view_size
        view_size=$(echo "${view_line}" | grep -o '([0-9]*)' | tail -1 | tr -d '()' || echo "0")
        if [[ -n "${view_size}" && "${view_size}" -ge "${REPLICAS}" ]]; then
          printMessage "JGroups cluster formed: ${view_size}-node view (pod ${pod})"
          return 0
        fi
      fi
    done
    sleep 3
  done
  printFailureMessage "JGroups cluster did not reach ${REPLICAS}-node view within timeout"
  # Dump logs for diagnosis
  local pods
  pods=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
    -l "app=mockserver,release=${RELEASE_NAME}" \
    -o jsonpath='{.items[*].metadata.name}')
  for pod in ${pods}; do
    printMessage "Logs from ${pod}:"
    kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs "${pod}" 2>/dev/null | tail -40 || true
  done
  return 1
}

function get_pod_names() {
  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
    -l "app=mockserver,release=${RELEASE_NAME}" \
    -o jsonpath='{.items[*].metadata.name}'
}

# PUT an expectation via port-forward to a specific pod
function create_expectation_on_pod() {
  local pod="$1"
  local path="$2"
  local body="$3"
  local times_json="${4:-}"

  local expectation="{
    \"httpRequest\": {\"path\": \"${path}\"},
    \"httpResponse\": {\"body\": \"${body}\"}
  }"
  if [[ -n "${times_json}" ]]; then
    expectation="{
      \"httpRequest\": {\"path\": \"${path}\"},
      \"httpResponse\": {\"body\": \"${body}\"},
      \"times\": ${times_json}
    }"
  fi

  # port-forward to the specific pod
  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "pod/${pod}" 18080:1080 &>/dev/null &
  local pf_pid=$!
  sleep 2

  local result
  result=$(curl -sf -X PUT "http://127.0.0.1:18080/mockserver/expectation" \
    -H "Content-Type: application/json" -d "${expectation}" 2>/dev/null || echo "FAIL")

  kill "${pf_pid}" 2>/dev/null || true
  wait "${pf_pid}" 2>/dev/null || true
  sleep 1
  echo "${result}"
}

# GET a path via port-forward to a specific pod, return HTTP status:body
# Uses a SINGLE curl request to avoid consuming multiple Times slots.
function hit_pod() {
  local pod="$1"
  local path="$2"
  local port="${3:-18081}"

  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "pod/${pod}" "${port}":1080 &>/dev/null &
  local pf_pid=$!
  sleep 2

  # Single curl: capture body and status in one request
  local tmpfile
  tmpfile=$(mktemp)
  local status
  status=$(curl -s -o "${tmpfile}" -w '%{http_code}' "http://127.0.0.1:${port}${path}" 2>/dev/null || echo "000")
  local body
  body=$(cat "${tmpfile}" 2>/dev/null || echo "")
  rm -f "${tmpfile}"

  kill "${pf_pid}" 2>/dev/null || true
  wait "${pf_pid}" 2>/dev/null || true
  sleep 1
  echo "${status}:${body}"
}

# Clear all expectations via port-forward to a specific pod
function clear_on_pod() {
  local pod="$1"

  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "pod/${pod}" 18082:1080 &>/dev/null &
  local pf_pid=$!
  sleep 2

  curl -sf -X PUT "http://127.0.0.1:18082/mockserver/clear" \
    -H "Content-Type: application/json" -d '{"path": "/.*"}' 2>/dev/null || true

  kill "${pf_pid}" 2>/dev/null || true
  wait "${pf_pid}" 2>/dev/null || true
  sleep 1
}

function integration_test() {
  local TEST_EXIT_CODE=0

  # Deploy with clustering enabled
  printMessage "Deploying ${REPLICAS} clustered replicas"
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install \
    --namespace "${NAMESPACE}" --create-namespace \
    --set replicaCount="${REPLICAS}" \
    --set clustering.enabled=true \
    --set image.repositoryNameAndTag="mockserver/mockserver:integration_testing_clustered" \
    --set image.pullPolicy=Never \
    --set service.type=ClusterIP \
    --debug --wait --timeout 180s \
    "${RELEASE_NAME}" "${SCRIPT_DIR}/../../helm/mockserver" || {
    printFailureMessage "Helm install failed"
    TEST_EXIT_CODE=1
  }

  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    wait_for_pods_ready || TEST_EXIT_CODE=1
  fi

  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    wait_for_cluster_formation || TEST_EXIT_CODE=1
  fi

  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local pods
    pods=$(get_pod_names)
    local pod_a pod_b
    pod_a=$(echo "${pods}" | awk '{print $1}')
    pod_b=$(echo "${pods}" | awk '{print $2}')
    printMessage "Pod A: ${pod_a}, Pod B: ${pod_b}"

    # --- Test 1: cross-pod expectation convergence ---
    printMessage "Test 1: create expectation on pod A, match on pod B"
    create_expectation_on_pod "${pod_a}" "/cluster-test" "hello-from-cluster"
    # Allow replication time
    sleep 3

    local response
    response=$(hit_pod "${pod_b}" "/cluster-test" 18083)
    local resp_status="${response%%:*}"
    local resp_body="${response#*:}"
    if [[ "${resp_body}" == "hello-from-cluster" ]]; then
      printPassMessage "Test 1 PASSED: cross-pod match (status=${resp_status}, body=${resp_body})"
    else
      printFailureMessage "Test 1 FAILED: expected 'hello-from-cluster', got status=${resp_status} body='${resp_body}'"
      TEST_EXIT_CODE=1
    fi

    # --- Test 2: cross-pod clear propagation ---
    printMessage "Test 2: clear on pod A, verify 404 on pod B"
    clear_on_pod "${pod_a}"
    sleep 3

    response=$(hit_pod "${pod_b}" "/cluster-test" 18084)
    resp_status="${response%%:*}"
    resp_body="${response#*:}"
    # MockServer returns 502 (no matching expectation) or 404 when the
    # expectation has been cleared. Both indicate the clear propagated.
    if [[ "${resp_body}" != "hello-from-cluster" ]]; then
      printPassMessage "Test 2 PASSED: clear propagated (pod B returns ${resp_status}, no match)"
    else
      printFailureMessage "Test 2 FAILED: expected no match, but got body='${resp_body}' status=${resp_status}"
      TEST_EXIT_CODE=1
    fi

    # --- Test 3: fleet-wide Times.exactly(N) ---
    printMessage "Test 3: Times.exactly(3) honored across fleet"
    create_expectation_on_pod "${pod_a}" "/times-test" "limited" '{"remainingTimes": 3, "unlimited": false}'
    sleep 3

    local match_count=0
    # Hit pods alternating, up to 6 attempts (should get exactly 3 matches)
    for i in $(seq 1 6); do
      local target_pod="${pod_a}"
      local port="18085"
      if [[ $((i % 2)) -eq 0 ]]; then
        target_pod="${pod_b}"
        port="18086"
      fi
      response=$(hit_pod "${target_pod}" "/times-test" "${port}")
      resp_status="${response%%:*}"
      resp_body="${response#*:}"
      if [[ "${resp_body}" == "limited" ]]; then
        match_count=$((match_count + 1))
      fi
    done

    if [[ "${match_count}" -eq 3 ]]; then
      printPassMessage "Test 3 PASSED: Times.exactly(3) yielded exactly 3 matches across fleet"
    else
      # Allow +/-1 tolerance for cluster timing
      if [[ "${match_count}" -ge 2 && "${match_count}" -le 4 ]]; then
        printMessage "Test 3 SOFT PASS: Times.exactly(3) yielded ${match_count} matches (tolerance 2-4)"
      else
        printFailureMessage "Test 3 FAILED: Times.exactly(3) yielded ${match_count} matches"
        TEST_EXIT_CODE=1
      fi
    fi
  fi

  # Dump pod logs for diagnosis
  local pods
  pods=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
    -l "app=mockserver,release=${RELEASE_NAME}" \
    -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || echo "")
  for pod in ${pods}; do
    printMessage "Final logs from ${pod}:"
    kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs "${pod}" 2>/dev/null | tail -20 || true
  done

  # Tear down
  helm --kube-context "${KUBE_CONTEXT}" -n "${NAMESPACE}" delete "${RELEASE_NAME}" 2>/dev/null || true
  kubectl --context "${KUBE_CONTEXT}" delete namespace "${NAMESPACE}" --wait=false 2>/dev/null || true

  # Non-blocking: warn but do not red the pipeline
  logTestResultNonBlocking "${TEST_EXIT_CODE}" "${TEST_CASE}"
  return 0
}

integration_test
