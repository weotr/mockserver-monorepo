#!/usr/bin/env bash
set -euo pipefail

JAR_DIR="mockserver/mockserver-netty/target"
SHADED_DIR="mockserver/mockserver-netty-no-dependencies/target"

echo "--- :buildkite: Downloading shaded JAR artifact"
if command -v buildkite-agent &>/dev/null && buildkite-agent artifact download "$SHADED_DIR/mockserver-netty-no-dependencies-*.jar" . 2>/dev/null; then
  SHADED_JAR=$(ls "$SHADED_DIR"/mockserver-netty-no-dependencies-*.jar 2>/dev/null \
    | grep -Ev -- '-(sources|javadoc)\.jar$|/original-mockserver-netty-no-dependencies-' | head -1)
  if [ -z "$SHADED_JAR" ]; then
    echo "Error: shaded JAR not found after artifact download"
    exit 1
  fi
  mkdir -p "$JAR_DIR"
  VERSION=$(basename "$SHADED_JAR" | sed -E 's/^mockserver-netty-no-dependencies-(.+)\.jar$/\1/')
  if [ -z "$VERSION" ] || [ "$VERSION" = "$(basename "$SHADED_JAR")" ]; then
    echo "Error: could not extract version from $SHADED_JAR"
    exit 1
  fi
  JAR_NAME="mockserver-netty-${VERSION}-jar-with-dependencies.jar"
  cp "$SHADED_JAR" "$JAR_DIR/$JAR_NAME"
else
  echo "No artifact available — building JAR from source"
  echo "--- :maven: Building mockserver-netty shaded JAR"
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  "$SCRIPT_DIR/../run-in-docker.sh" \
    -i mockserver/mockserver:maven \
    -m 7g \
    -w /build/mockserver \
    -e "MAVEN_OPTS=-Xms2048m -Xmx6144m" \
    -- ./mvnw -pl mockserver-netty -am package -DskipTests -q
  JAR_NAME=$(ls "${JAR_DIR}"/mockserver-netty-*-jar-with-dependencies.jar 2>/dev/null | head -1 | xargs basename)
fi

if [ -z "$JAR_NAME" ] || [ ! -f "$JAR_DIR/$JAR_NAME" ]; then
  echo "Error: jar-with-dependencies JAR not found in $JAR_DIR"
  exit 1
fi

echo "--- :docker: Building MockServer Docker image for testing"
cp "$JAR_DIR/$JAR_NAME" docker/mockserver-netty-jar-with-dependencies.jar
docker build --no-cache -t mockserver/mockserver:integration_testing --build-arg source=copy docker
rm docker/mockserver-netty-jar-with-dependencies.jar

echo "--- :k8s: Installing k3d (if needed)"
K3D_VERSION="v5.7.5"
K3D_DIR="${PWD}/.tmp/bin"
export PATH="${K3D_DIR}:${PATH}"
if ! command -v k3d &>/dev/null || [[ "$(k3d version 2>/dev/null | head -1)" != *"${K3D_VERSION#v}"* ]]; then
  mkdir -p "$K3D_DIR"
  ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
  curl -fsSL "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-${ARCH}" -o "$K3D_DIR/k3d"
  chmod +x "$K3D_DIR/k3d"
  k3d version
fi

echo "--- :helm: Running Helm integration tests"
export SKIP_JAVA_BUILD=true
export SKIP_DOCKER_BUILD_MOCKSERVER=true
export SKIP_DOCKER_REBUILD_CLIENT=true
export SKIP_DOCKER_TESTS=true
export DELETE_CLUSTER=true

exec container_integration_tests/integration_tests.sh
