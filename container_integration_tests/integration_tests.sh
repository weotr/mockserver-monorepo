#!/usr/bin/env bash
# shellcheck disable=SC2155

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/logging.sh"
source "${SCRIPT_DIR}/helm-deploy.sh"
source "${SCRIPT_DIR}/docker-compose.sh"

# SKIP_JAVA_BUILD=true ./integration_tests.sh
# SKIP_HELM_TESTS=true SKIP_JAVA_BUILD=true DOCKER_BUILD=true ./container_integration_tests/integration_tests.sh

function build_docker() {
  runCommand "cd ${SCRIPT_DIR}"
  if [[ "${SKIP_JAVA_BUILD:-}" != "true" ]]; then
    runCommand "(cd ${SCRIPT_DIR}/../mockserver && ./mvnw -DskipTests=true package)"
  fi
  if [[ "${SKIP_DOCKER_BUILD_MOCKSERVER:-}" != "true" ]]; then
    runCommand "cp ${SCRIPT_DIR}/../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar ${SCRIPT_DIR}/../docker/mockserver-netty-jar-with-dependencies.jar"
    runCommand "docker build --no-cache -t mockserver/mockserver:integration_testing --build-arg source=copy ${SCRIPT_DIR}/../docker"
    runCommand "rm ${SCRIPT_DIR}/../docker/mockserver-netty-jar-with-dependencies.jar"
  fi
  if [[ "${SKIP_DOCKER_REBUILD_CLIENT:-}" != "true" ]]; then
    runCommand "docker build -t mockserver/mockserver:integration_testing_client_curl -f ${SCRIPT_DIR}/client_docker_images/CurlClientDockerfile ${SCRIPT_DIR}/client_docker_images"
  fi
}

function test() {
  export TEST_CASE="${1}"
  printMessage "Running Test: \"${TEST_CASE}\""
  runCommand "cd ${SCRIPT_DIR}/${TEST_CASE}"
  runCommand "./integration_test.sh" || return 1
  runCommand "cd ${SCRIPT_DIR}"
}

# 5c.4 - build each published variant Dockerfile and confirm it boots and
# responds to /mockserver/status. Catches Dockerfile drift early without
# running the whole test suite per variant.
#
# Uses --build-arg source=copy + a locally-built JAR rather than the default
# source=download path. The download path resolves the latest RELEASE from
# Maven Central, which doesn't exist for in-development versions (e.g.,
# 6.1.1-SNAPSHOT before publication) and would always 404 in CI.
function smoke_test_variant() {
  local variant="$1"
  local tag="mockserver/mockserver:smoke-${variant}"
  local container="smoke-${variant}"
  local variant_dir="${SCRIPT_DIR}/../docker/${variant}"
  local jar_path="${variant_dir}/mockserver-netty-jar-with-dependencies.jar"
  export TEST_CASE="docker_variant_smoke_${variant}"
  printMessage "Smoke test: variant \"${variant}\""

  local exit_code=0
  # Locate locally-built fat jar; build_docker() already copies one to
  # docker/ as part of the main image build, so reuse it for variants too.
  local source_jar
  source_jar=$(ls "${SCRIPT_DIR}"/../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar 2>/dev/null | head -1)
  if [[ -z "${source_jar}" ]]; then
    source_jar=$(ls "${SCRIPT_DIR}"/../docker/mockserver-netty-jar-with-dependencies.jar 2>/dev/null | head -1)
  fi
  if [[ -z "${source_jar}" ]]; then
    printFailureMessage "${variant}: no local mockserver-netty fat jar found - build it first"
    logTestResult "1" "${TEST_CASE}"
    return 1
  fi

  cp "${source_jar}" "${jar_path}"
  # local Dockerfile is single-stage and expects the JAR in build context;
  # root + snapshot Dockerfiles take --build-arg source=copy.
  local build_args=""
  if [[ "${variant}" != "local" ]]; then
    build_args="--build-arg source=copy"
  fi

  runCommand "docker build ${build_args} -t ${tag} ${variant_dir}" || exit_code=1
  rm -f "${jar_path}"

  if [[ ${exit_code} -eq 0 ]]; then
    runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
    runCommand "docker run -d --name ${container} -p 0:1080 ${tag}"
    local host_port
    host_port=$(docker port "${container}" 1080 2>/dev/null | head -1 | awk -F: '{print $NF}')
    if [[ -z "${host_port}" ]]; then
      printFailureMessage "${variant}: could not resolve host port for container ${container}"
      exit_code=1
    else
      # poll for readiness up to ~30s instead of a fixed sleep
      local i status
      status=000
      for i in $(seq 1 15); do
        status=$(curl -sf -o /dev/null -w '%{http_code}' -X PUT "http://localhost:${host_port}/mockserver/status" 2>/dev/null || echo "000")
        [[ "${status}" == "200" ]] && break
        sleep 2
      done
      if [[ "${status}" != "200" ]]; then
        printFailureMessage "${variant}: /mockserver/status returned \"${status}\" (expected 200)"
        runCommand "docker logs ${container} | tail -30 || true"
        exit_code=1
      fi
    fi
    runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  fi
  runCommand "docker rmi -f ${tag} >/dev/null 2>&1 || true"
  logTestResult "${exit_code}" "${TEST_CASE}"
  return ${exit_code}
}

function run_all_tests() {
  export PASS_LOG_FILE=$(mktemp)
  export FAIL_LOG_FILE=$(mktemp)

  if [[ "${SKIP_ALL_TESTS:-}" != "true" ]]; then
    set +euo pipefail
    if [[ "${SKIP_DOCKER_TESTS:-}" != "true" ]]; then
      # docker compose test
      test "docker_compose_forward_with_override"
      test "docker_compose_remote_host_and_port_by_environment_variable"
      test "docker_compose_server_port_by_command"
      test "docker_compose_server_port_by_environment_variable_long_name"
      test "docker_compose_server_port_by_environment_variable_short_name"
      test "docker_compose_without_server_port"
      test "docker_compose_with_expectation_initialiser"
      test "docker_compose_with_persisted_expectations"
      test "docker_compose_with_server_port_from_default_properties_file"
      test "docker_compose_with_server_port_from_custom_properties_file"
      test "docker_compose_with_mtls"
      test "docker_compose_jvm_options"
      test "docker_compose_libs_classpath"
      test "docker_compose_graceful_shutdown"
      # 5c.4 - per-variant smoke tests (root/snapshot/local Dockerfiles).
      # Same gate as docker_compose_* tests: they share the same JAR + docker
      # daemon, and the helm-only CI step (helm-integration-test.sh) sets
      # SKIP_DOCKER_TESTS=true to skip both.
      if [[ "${SKIP_VARIANT_TESTS:-}" != "true" ]]; then
        smoke_test_variant "root" || true
        smoke_test_variant "snapshot" || true
        smoke_test_variant "local" || true
      fi
      clean-up-docker-containers
    fi
    if [[ "${SKIP_HELM_TESTS:-}" != "true" ]]; then
      # helm test
      start-up-k8s
      test "helm_default_config"
      test "helm_local_docker_container"
      test "helm_custom_server_port"
      test "helm_remote_host_and_port"
      test "helm_inline_config"
      test "helm_configmap_injection"
      test "helm_mockserver_config_chart"
      tear-down-k8s
    fi
    set -euo pipefail
  fi

  printMessage "TEST SUMMARY"
  if [[ -s "${PASS_LOG_FILE}" ]]; then
    NUMBER_OF_PASSED_TESTS=$(cat "${PASS_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "PASSED: ${NUMBER_OF_PASSED_TESTS}"
    cat "${PASS_LOG_FILE}"
    rm "${PASS_LOG_FILE}"
    printf "\n\n"
  fi
  if [[ -s "${FAIL_LOG_FILE}" ]]; then
    NUMBER_OF_FAILED_TESTS=$(cat "${FAIL_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "FAILED: ${NUMBER_OF_FAILED_TESTS}"
    cat "${FAIL_LOG_FILE}"
    rm "${FAIL_LOG_FILE}"
    printf "\n\n"
    EXIT_CODE=1
  fi

  exit ${EXIT_CODE:-0}
}

build_docker
run_all_tests
