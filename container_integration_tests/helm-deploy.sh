#!/usr/bin/env bash

set -euo pipefail

CLUSTER_NAME="mockserver"
KUBE_CONTEXT="k3d-${CLUSTER_NAME}"

function start-up-k8s() {
  local SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
  if [[ "${REBUILD_CLUSTER:-false}" == "true" ]]; then
    runCommand "k3d cluster delete ${CLUSTER_NAME}"
  fi

  if k3d cluster list 2>&1 | grep -qw "${CLUSTER_NAME}"; then
    printMessage "Found existing cluster"
  else
    runCommand "k3d cluster create --config ${SCRIPT_DIR}/k3d-config.yaml"
  fi

  runCommand "k3d image import --cluster ${CLUSTER_NAME} mockserver/mockserver:integration_testing"
}

function tear-down-k8s() {
  if [[ "${DELETE_CLUSTER:-false}" == "true" ]]; then
    runCommand "k3d cluster delete ${CLUSTER_NAME}"
  fi
}

function start-up() {
  local SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
  local namespace="${2:-mockserver}"
  local port="${3:-1080}"
  runCommand "helm --kube-context ${KUBE_CONTEXT} upgrade --install --namespace ${namespace} --create-namespace ${1:-} --debug --wait --version 5.15.0 ${namespace} ${SCRIPT_DIR}/../helm/mockserver"
  # Kill any stale port-forward holding this local port, then WAIT for the port to
  # actually free before binding a new one. A leaked background forward from a prior
  # test is what causes the flaky "address already in use" on bind.
  runCommand "(ps -ef | grep port-forward | grep ${port} | awk '{ print \$2 }' | xargs kill) || true"
  runCommand "for _ in \$(seq 1 15); do (exec 3<>/dev/tcp/127.0.0.1/${port}) 2>/dev/null && { exec 3>&-; sleep 1; } || break; done"
  runCommand "kubectl --context ${KUBE_CONTEXT} --namespace ${namespace} port-forward svc/${namespace} ${port}:${port} >/dev/null 2>&1 &"
  export MOCKSERVER_HOST=127.0.0.1:${port}
  # Poll until the forward actually serves rather than a fixed sleep (flaky under load).
  runCommand "for _ in \$(seq 1 30); do curl -sf -o /dev/null -X PUT \"http://127.0.0.1:${port}/mockserver/status\" && break; sleep 1; done"
}

function run-helm-test() {
  printMessage "Running helm test for release: ${1:-mockserver}"
  runCommand "helm --kube-context ${KUBE_CONTEXT} --namespace ${1:-mockserver} test ${1:-mockserver} --timeout 60s"
}

function tear-down() {
  runCommand "helm --kube-context ${KUBE_CONTEXT} --namespace ${1:-mockserver} delete ${1:-mockserver}"
  runCommand "(ps -ef | grep port-forward | grep ${2:-1080} | awk '{ print \$2 }' | xargs kill) || true"
}

function container-logs() {
  printMessage "${1:-mockserver} logs"
  runCommand "kubectl --context ${KUBE_CONTEXT} --namespace ${1:-mockserver} logs $(kubectl --context ${KUBE_CONTEXT} --namespace ${1:-mockserver} get po -l app=mockserver,release=${1:-mockserver} -o=jsonpath='{.items[0].metadata.name}')"
}
