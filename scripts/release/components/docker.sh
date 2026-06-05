#!/usr/bin/env bash
# Build and push the MockServer Docker images (linux/amd64 + linux/arm64) to
# Docker Hub and AWS ECR Public.
#
# Dry-run: docker buildx build (local, no --push), skip ECR login.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd docker
require_cmd curl
require_release_inputs
skip_unless_release_type "docker" full,post-maven,docker-only

log_step "Publish Docker images $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

# ---- Locate or fetch shaded JAR -------------------------------------------
# Since the cbc7f92f8 refactor the shaded jar is the main artifact of the
# mockserver-netty-no-dependencies sibling module, not a classifier on
# mockserver-netty. Filter out -sources/-javadoc siblings.
cd "$REPO_ROOT"
find_local_shaded() {
  find mockserver/mockserver-netty-no-dependencies/target \
    -name 'mockserver-netty-no-dependencies-*.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    ! -name 'original-*' \
    -print -quit 2>/dev/null || true
}
SHADED_JAR=$(find_local_shaded)
if [[ -z "$SHADED_JAR" ]]; then
  log_info "Local shaded JAR not found — downloading from Maven Central"
  mkdir -p mockserver/mockserver-netty-no-dependencies/target
  SHADED_JAR="mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-${RELEASE_VERSION}.jar"
  CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-netty-no-dependencies/${RELEASE_VERSION}/mockserver-netty-no-dependencies-${RELEASE_VERSION}.jar"
  if is_dry_run && ! curl -sf -I "$CENTRAL_URL" >/dev/null 2>&1; then
    log_dry "skip: download $RELEASE_VERSION JAR (not yet on Maven Central — would normally wait)"
    # Use a locally-built shaded jar as a stand-in for local docker build test.
    SHADED_JAR=$(find_local_shaded)
    if [[ -z "$SHADED_JAR" ]]; then
      log_dry "no local JAR available — running 'mvn package' to produce one"
      in_maven -w /build/mockserver \
        -- mvn -DskipTests -pl mockserver-netty-no-dependencies -am package
      SHADED_JAR=$(find_local_shaded)
    fi
  else
    curl -fsSL --max-time 300 --connect-timeout 30 --retry 3 --retry-delay 5 \
      -o "$SHADED_JAR" \
      "$CENTRAL_URL"
  fi
fi
[[ -n "$SHADED_JAR" && -f "$SHADED_JAR" ]] || { log_error "No shaded JAR available"; exit 1; }
log_info "Using JAR: $SHADED_JAR"
cp "$SHADED_JAR" docker/local/mockserver-netty-jar-with-dependencies.jar
cp "$SHADED_JAR" docker/graaljs/mockserver-netty-jar-with-dependencies.jar
cp "$SHADED_JAR" docker/clustered/mockserver-netty-jar-with-dependencies.jar

# Stage a CA bundle into the graaljs build context. The alpine stages COPY it
# in and (when non-empty) trust it before `apk add`, so builds behind a
# corporate TLS-inspecting proxy succeed. Empty file in CI is a no-op.
LOCAL_CA="${LOCAL_CA_BUNDLE:-${NODE_EXTRA_CA_CERTS:-${AWS_CA_BUNDLE:-}}}"
if [[ -n "$LOCAL_CA" && -f "$LOCAL_CA" ]]; then
  log_info "Staging local CA into docker/graaljs build context ($LOCAL_CA)"
  cp "$LOCAL_CA" docker/graaljs/ca-bundle.pem
else
  : > docker/graaljs/ca-bundle.pem
fi

# ---- Resolve Infinispan clustered-state libs for the -clustered image ------
# Use Maven to resolve the transitive runtime dependencies of the
# mockserver-state-infinispan module. The module JAR itself is downloaded
# separately from Maven Central (it's excluded by -DexcludeGroupIds).
find_local_infinispan_jar() {
  find mockserver/mockserver-state-infinispan/target \
    -name 'mockserver-state-infinispan-*.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    ! -name '*-tests.jar' \
    -print -quit 2>/dev/null || true
}

mkdir -p docker/clustered/libs
INFINISPAN_JAR=$(find_local_infinispan_jar)
if [[ -z "$INFINISPAN_JAR" ]]; then
  log_info "Infinispan JAR not found locally — downloading from Maven Central"
  INFINISPAN_JAR="mockserver/mockserver-state-infinispan/target/mockserver-state-infinispan-${RELEASE_VERSION}.jar"
  INFINISPAN_CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-state-infinispan/${RELEASE_VERSION}/mockserver-state-infinispan-${RELEASE_VERSION}.jar"
  if is_dry_run && ! curl -sf -I "$INFINISPAN_CENTRAL_URL" >/dev/null 2>&1; then
    log_dry "skip: download infinispan $RELEASE_VERSION JAR (not yet on Maven Central)"
    INFINISPAN_JAR=$(find_local_infinispan_jar)
    if [[ -z "$INFINISPAN_JAR" ]]; then
      log_dry "no local infinispan JAR available — running 'mvn package' to produce one"
      in_maven -w /build/mockserver \
        -- mvn -DskipTests -pl mockserver-state-infinispan -am package
      INFINISPAN_JAR=$(find_local_infinispan_jar)
    fi
  else
    mkdir -p mockserver/mockserver-state-infinispan/target
    curl -fsSL --max-time 300 --connect-timeout 30 --retry 3 --retry-delay 5 \
      -o "$INFINISPAN_JAR" \
      "$INFINISPAN_CENTRAL_URL"
  fi
fi

BUILD_CLUSTERED=false
if [[ -n "$INFINISPAN_JAR" && -f "$INFINISPAN_JAR" ]]; then
  log_info "Using infinispan JAR: $INFINISPAN_JAR"
  cp "$INFINISPAN_JAR" docker/clustered/libs/

  # Resolve transitive runtime dependencies (Infinispan, JGroups, etc.)
  log_info "Resolving infinispan transitive dependencies"
  in_maven -w /build/mockserver \
    -- mvn -pl mockserver-state-infinispan dependency:copy-dependencies \
      -DincludeScope=runtime -DexcludeGroupIds=org.mock-server \
      -DoutputDirectory=/build/docker/clustered/libs
  BUILD_CLUSTERED=true
else
  log_info "WARNING: Infinispan JAR not available — skipping clustered image build"
fi

# ---- Auth (skipped in dry-run) --------------------------------------------
if ! is_dry_run; then
  log_info "Login to Docker Hub + ECR Public"
  "$REPO_ROOT/.buildkite/scripts/docker-login.sh"
  "$REPO_ROOT/.buildkite/scripts/ecr-login.sh"
fi

FULL_TAG="mockserver-$RELEASE_VERSION"
SHORT_TAG="$RELEASE_VERSION"
# ECR Public repository URI. The registry alias is AWS-assigned (it is not the
# repository name), so resolve it at run time rather than hard-coding. A real
# run must fail loudly if the lookup fails; a local dry-run without AWS
# credentials falls back to a placeholder — the ECR tags are built but never
# pushed in dry-run.
if is_dry_run; then
  ECR_REPO=$(aws ecr-public describe-repositories --region us-east-1 \
    --repository-names mockserver --query 'repositories[0].repositoryUri' \
    --output text 2>/dev/null || echo "public.ecr.aws/dry-run/mockserver")
else
  ECR_REPO=$(aws ecr-public describe-repositories --region us-east-1 \
    --repository-names mockserver --query 'repositories[0].repositoryUri' --output text)
fi

log_info "Build images"

# ---- Locate or build webhook fat JAR ----------------------------------------
find_local_webhook_jar() {
  find mockserver/mockserver-k8s-webhook/target \
    -name 'mockserver-k8s-webhook-*-jar-with-dependencies.jar' \
    -print -quit 2>/dev/null || true
}
WEBHOOK_JAR=$(find_local_webhook_jar)
if [[ -z "$WEBHOOK_JAR" ]]; then
  log_info "Webhook fat JAR not found locally — downloading from Maven Central"
  mkdir -p mockserver/mockserver-k8s-webhook/target
  WEBHOOK_JAR="mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-${RELEASE_VERSION}-jar-with-dependencies.jar"
  WEBHOOK_CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-k8s-webhook/${RELEASE_VERSION}/mockserver-k8s-webhook-${RELEASE_VERSION}-jar-with-dependencies.jar"
  if is_dry_run && ! curl -sf -I "$WEBHOOK_CENTRAL_URL" >/dev/null 2>&1; then
    log_dry "skip: download webhook $RELEASE_VERSION JAR (not yet on Maven Central)"
    WEBHOOK_JAR=$(find_local_webhook_jar)
    if [[ -z "$WEBHOOK_JAR" ]]; then
      log_dry "no local webhook JAR available — running 'mvn package' to produce one"
      in_maven -w /build/mockserver \
        -- mvn -DskipTests -pl mockserver-k8s-webhook -am package
      WEBHOOK_JAR=$(find_local_webhook_jar)
    fi
  else
    curl -fsSL --max-time 300 --connect-timeout 30 --retry 3 --retry-delay 5 \
      -o "$WEBHOOK_JAR" \
      "$WEBHOOK_CENTRAL_URL"
  fi
fi
if [[ -n "$WEBHOOK_JAR" && -f "$WEBHOOK_JAR" ]]; then
  log_info "Using webhook JAR: $WEBHOOK_JAR"
  cp "$WEBHOOK_JAR" docker/webhook/mockserver-webhook.jar
  BUILD_WEBHOOK=true
else
  log_info "WARNING: Webhook JAR not available — skipping webhook image build"
  BUILD_WEBHOOK=false
fi

if is_dry_run; then
  # Local single-arch via the default daemon. Plain `docker build` reuses
  # Docker Desktop's CA trust (whereas a fresh buildx builder does not).
  docker build \
    --tag "mockserver/mockserver:$FULL_TAG" \
    --tag "mockserver/mockserver:$SHORT_TAG" \
    --tag "mockserver/mockserver:latest" \
    --tag "${ECR_REPO}:$FULL_TAG" \
    --tag "${ECR_REPO}:$SHORT_TAG" \
    --tag "${ECR_REPO}:latest" \
    docker/local

  docker build \
    --build-arg source=copy \
    --tag "mockserver/mockserver:$FULL_TAG-graaljs" \
    --tag "mockserver/mockserver:$SHORT_TAG-graaljs" \
    --tag "mockserver/mockserver:latest-graaljs" \
    --tag "${ECR_REPO}:$FULL_TAG-graaljs" \
    --tag "${ECR_REPO}:$SHORT_TAG-graaljs" \
    --tag "${ECR_REPO}:latest-graaljs" \
    docker/graaljs

  if [[ "$BUILD_CLUSTERED" == "true" ]]; then
    docker build \
      --tag "mockserver/mockserver:clustered-$FULL_TAG" \
      --tag "mockserver/mockserver:clustered-$SHORT_TAG" \
      --tag "mockserver/mockserver:clustered-latest" \
      --tag "${ECR_REPO}:clustered-$FULL_TAG" \
      --tag "${ECR_REPO}:clustered-$SHORT_TAG" \
      --tag "${ECR_REPO}:clustered-latest" \
      docker/clustered
  fi

  if [[ "$BUILD_WEBHOOK" == "true" ]]; then
    docker build \
      --tag "mockserver/mockserver-webhook:$FULL_TAG" \
      --tag "mockserver/mockserver-webhook:$SHORT_TAG" \
      --tag "mockserver/mockserver-webhook:latest" \
      --tag "${ECR_REPO}-webhook:$FULL_TAG" \
      --tag "${ECR_REPO}-webhook:$SHORT_TAG" \
      --tag "${ECR_REPO}-webhook:latest" \
      docker/webhook
  fi

  log_dry "skip: push to Docker Hub + ECR (built locally, not pushed)"
else
  # CI: multi-arch + push via buildx.
  docker buildx create --use --name multiarch 2>/dev/null || docker buildx use multiarch

  docker buildx build \
    --platform "linux/amd64,linux/arm64" \
    --push \
    --tag "mockserver/mockserver:$FULL_TAG" \
    --tag "mockserver/mockserver:$SHORT_TAG" \
    --tag "mockserver/mockserver:latest" \
    --tag "${ECR_REPO}:$FULL_TAG" \
    --tag "${ECR_REPO}:$SHORT_TAG" \
    --tag "${ECR_REPO}:latest" \
    docker/local

  docker buildx build \
    --platform "linux/amd64,linux/arm64" \
    --push \
    --build-arg source=copy \
    --tag "mockserver/mockserver:$FULL_TAG-graaljs" \
    --tag "mockserver/mockserver:$SHORT_TAG-graaljs" \
    --tag "mockserver/mockserver:latest-graaljs" \
    --tag "${ECR_REPO}:$FULL_TAG-graaljs" \
    --tag "${ECR_REPO}:$SHORT_TAG-graaljs" \
    --tag "${ECR_REPO}:latest-graaljs" \
    docker/graaljs

  if [[ "$BUILD_CLUSTERED" == "true" ]]; then
    # Error-isolated: a clustered image push failure must never abort the
    # release — the main + GraalJS images have already been published above.
    echo "--- :docker: Building and pushing clustered image variant"
    if ! docker buildx build \
      --platform "linux/amd64,linux/arm64" \
      --push \
      --tag "mockserver/mockserver:clustered-$FULL_TAG" \
      --tag "mockserver/mockserver:clustered-$SHORT_TAG" \
      --tag "mockserver/mockserver:clustered-latest" \
      --tag "${ECR_REPO}:clustered-$FULL_TAG" \
      --tag "${ECR_REPO}:clustered-$SHORT_TAG" \
      --tag "${ECR_REPO}:clustered-latest" \
      docker/clustered; then
      log_info "WARNING: clustered image push failed — continuing (main images already published)"
    fi
  fi

  if [[ "$BUILD_WEBHOOK" == "true" ]]; then
    # Push webhook to Docker Hub first (primary registry used by Helm chart).
    # Error-isolated: a webhook push failure must never abort the release —
    # the main + GraalJS images have already been published above.
    if ! docker buildx build \
      --platform "linux/amd64,linux/arm64" \
      --push \
      --tag "mockserver/mockserver-webhook:$FULL_TAG" \
      --tag "mockserver/mockserver-webhook:$SHORT_TAG" \
      --tag "mockserver/mockserver-webhook:latest" \
      docker/webhook; then
      log_info "WARNING: webhook Docker Hub push failed — continuing (main images already published)"
    fi
    # Push webhook to ECR separately — the ECR repo may not be provisioned yet.
    if ! docker buildx build \
      --platform "linux/amd64,linux/arm64" \
      --push \
      --tag "${ECR_REPO}-webhook:$FULL_TAG" \
      --tag "${ECR_REPO}-webhook:$SHORT_TAG" \
      --tag "${ECR_REPO}-webhook:latest" \
      docker/webhook; then
      log_info "WARNING: webhook ECR push failed — continuing (Docker Hub is the primary registry)"
    fi
  fi
fi

log_info "Docker publish complete"
