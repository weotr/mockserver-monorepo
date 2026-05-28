#!/usr/bin/env bash
set -euo pipefail

JAR_DIR="mockserver/mockserver-netty/target"
SHADED_DIR="mockserver/mockserver-netty-no-dependencies/target"

echo "--- :buildkite: Downloading shaded JAR artifact"
if command -v buildkite-agent &>/dev/null && buildkite-agent artifact download "$SHADED_DIR/mockserver-netty-no-dependencies-*.jar" . 2>/dev/null; then
  shopt -s nullglob
  SHADED_JAR=""
  for f in "$SHADED_DIR"/mockserver-netty-no-dependencies-*.jar; do
    case "$(basename "$f")" in
      *-sources.jar|*-javadoc.jar|original-*) continue ;;
    esac
    SHADED_JAR="$f"
    break
  done
  shopt -u nullglob
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

echo "--- :helm: Installing helm (if needed)"
# Build agents are spot instances and helm pre-installation is inconsistent
# across AMI snapshots (build #46 missing helm while #45 had it on a different
# agent of the same fleet). Install + SHA256-verify explicitly so behaviour
# is deterministic. SHA256 from
# https://get.helm.sh/helm-${HELM_VERSION}-linux-${ARCH}.tar.gz.sha256sum
HELM_VERSION="v3.16.4"
HELM_BIN_DIR="${PWD}/.tmp/bin"
export PATH="${HELM_BIN_DIR}:${PATH}"

declare -A HELM_SHA256=(
  [amd64]="fc307327959aa38ed8f9f7e66d45492bb022a66c3e5da6063958254b9767d179"
  [arm64]="d3f8f15b3d9ec8c8678fbf3280c3e5902efabe5912e2f9fcf29107efbc8ead69"
)

if ! command -v helm &>/dev/null || [[ "$(helm version --short 2>/dev/null)" != *"${HELM_VERSION}"* ]]; then
  mkdir -p "$HELM_BIN_DIR"
  ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
  HELM_TGZ="${HELM_BIN_DIR}/helm.tar.gz"
  curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-${ARCH}.tar.gz" -o "$HELM_TGZ"

  EXPECTED_SHA="${HELM_SHA256[$ARCH]:-}"
  if [[ -z "$EXPECTED_SHA" ]]; then
    echo "ERROR: no SHA256 pin for helm on $ARCH - refusing to install untrusted binary" >&2
    exit 1
  fi
  echo "${EXPECTED_SHA}  ${HELM_TGZ}" | sha256sum -c -

  tar -xzf "$HELM_TGZ" -C "$HELM_BIN_DIR" --strip-components=1 "linux-${ARCH}/helm"
  chmod +x "$HELM_BIN_DIR/helm"
  rm -f "$HELM_TGZ"
  helm version --short
fi

echo "--- :k8s: Installing k3d (if needed)"
K3D_VERSION="v5.7.5"
K3D_DIR="${PWD}/.tmp/bin"
export PATH="${K3D_DIR}:${PATH}"

# F-BK-05: pin and verify the k3d binary by SHA256. Update these values when
# bumping K3D_VERSION — published at
# https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/checksums.txt
declare -A K3D_SHA256=(
  [amd64]="5d3f22817d9e163ab6ed43572189dd49fe724d7a6948075b570067747eca8d3f" # k3d-linux-amd64
  [arm64]="ac12fcf8e35481769e173c96d3fa70dc581826482d927b94a560a3375df2621e" # k3d-linux-arm64
)

if ! command -v k3d &>/dev/null || [[ "$(k3d version 2>/dev/null | head -1)" != *"${K3D_VERSION#v}"* ]]; then
  mkdir -p "$K3D_DIR"
  ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
  curl -fsSL "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-${ARCH}" -o "$K3D_DIR/k3d"

  EXPECTED_SHA="${K3D_SHA256[$ARCH]:-}"
  if [[ -z "$EXPECTED_SHA" ]]; then
    echo "ERROR: no SHA256 pin for k3d on $ARCH — refusing to install untrusted binary" >&2
    exit 1
  fi
  echo "${EXPECTED_SHA}  ${K3D_DIR}/k3d" | sha256sum -c -

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
