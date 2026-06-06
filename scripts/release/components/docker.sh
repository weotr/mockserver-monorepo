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
  # Release images use the release-scoped Docker Hub token (release queue only).
  DOCKERHUB_SECRET_ID="mockserver-release/dockerhub" \
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

  # ---- Cosign-sign pushed Docker images ------------------------------------
  # Sign by digest so the signature binds to the exact manifest, not a mutable
  # tag. Uses the SAME cosign key infrastructure as helm.sh. NO-OP until a
  # signing key is stored at mockserver-release/cosign-key (keys: key, password)
  # — the describe-secret guard skips this entirely otherwise.
  # Signing is additive and STRICTLY non-fatal: a failure here never aborts
  # the release — the images are already pushed.
  # Resolve a usable cosign binary into $_cosign_bin. Prefer one already on PATH;
  # otherwise download the pinned release into .tmp/ (writable, no sudo needed —
  # docker.sh signs on the HOST, unlike helm.sh which installs inside a root
  # container). Pinned version + sha256 match helm.sh so both signers use the
  # same cosign. Returns non-zero if cosign cannot be made available.
  ensure_cosign() {
    if command -v cosign >/dev/null 2>&1; then
      _cosign_bin="cosign"
      return 0
    fi
    mkdir -p "$REPO_ROOT/.tmp"
    local target="$REPO_ROOT/.tmp/cosign"
    log_info "  cosign not on PATH — downloading pinned cosign v2.4.3 to .tmp/"
    # curl (not wget) — docker.sh runs on the bare agent host and only curl is
    # a guaranteed-present tool here (require_cmd curl); helm.sh uses wget only
    # because it runs inside a container image that bundles it.
    if ! curl -fsSL --max-time 120 -o "$target" "https://github.com/sigstore/cosign/releases/download/v2.4.3/cosign-linux-amd64"; then
      log_info "WARNING: failed to download cosign — skipping signing"
      return 1
    fi
    if ! echo "caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708  $target" | sha256sum -c - >/dev/null 2>&1; then
      log_info "WARNING: cosign checksum mismatch — refusing to use downloaded binary"
      rm -f "$target"
      return 1
    fi
    chmod +x "$target"
    _cosign_bin="$target"
    return 0
  }

  cosign_sign_docker_image() {
    local image_ref="$1"
    # Resolve the tag to a digest so we sign by content, not by mutable tag.
    local digest
    digest=$(docker buildx imagetools inspect "$image_ref" --format '{{.Digest}}' 2>/dev/null || true)
    if [[ -z "$digest" ]]; then
      log_info "WARNING: could not resolve digest for $image_ref — skipping cosign sign"
      return 1
    fi
    local repo="${image_ref%%:*}"
    local ref_by_digest="${repo}@${digest}"
    log_info "  cosign sign $ref_by_digest"
    "$_cosign_bin" sign --yes --key "$_cosign_key_file" "$ref_by_digest" || return 1
  }

  cosign_sign_docker_images() {
    local rc=0
    ensure_cosign || return 1
    mkdir -p "$REPO_ROOT/.tmp"
    _cosign_key_file="$REPO_ROOT/.tmp/cosign-key-docker.$$"
    local _cosign_pw_file="$REPO_ROOT/.tmp/cosign-pw-docker.$$"
    ( umask 077; load_secret "mockserver-release/cosign-key" "key" > "$_cosign_key_file" ) \
      || { rm -f "$_cosign_key_file"; return 1; }
    ( umask 077; load_secret "mockserver-release/cosign-key" "password" > "$_cosign_pw_file" ) \
      || { rm -f "$_cosign_key_file" "$_cosign_pw_file"; return 1; }
    export COSIGN_PASSWORD
    COSIGN_PASSWORD=$(cat "$_cosign_pw_file")
    rm -f "$_cosign_pw_file"

    # Sign primary images (Docker Hub + ECR) by their :latest tag (resolves to
    # the multi-arch manifest digest).
    local -a images_to_sign=(
      "mockserver/mockserver:$FULL_TAG"
      "${ECR_REPO}:$FULL_TAG"
      "mockserver/mockserver:$FULL_TAG-graaljs"
      "${ECR_REPO}:$FULL_TAG-graaljs"
    )
    if [[ "$BUILD_CLUSTERED" == "true" ]]; then
      images_to_sign+=(
        "mockserver/mockserver:clustered-$FULL_TAG"
        "${ECR_REPO}:clustered-$FULL_TAG"
      )
    fi
    if [[ "$BUILD_WEBHOOK" == "true" ]]; then
      images_to_sign+=(
        "mockserver/mockserver-webhook:$FULL_TAG"
      )
    fi
    for img in "${images_to_sign[@]}"; do
      cosign_sign_docker_image "$img" || {
        log_info "WARNING: cosign signing failed for $img (non-fatal)"
        rc=1
      }
    done
    rm -f "$_cosign_key_file"
    unset COSIGN_PASSWORD
    return $rc
  }

  if aws secretsmanager describe-secret --region "$REGION" \
       --secret-id mockserver-release/cosign-key >/dev/null 2>&1; then
    log_info "Cosign-signing pushed Docker images (mockserver-release/cosign-key found)"
    # cosign signs on the HOST (the images are already pushed to a registry).
    # cosign_sign_docker_images resolves a cosign binary via ensure_cosign,
    # installing the pinned release into .tmp/ if one is not already on PATH.
    if cosign_sign_docker_images; then
      log_info "Docker images signed with cosign"
    else
      log_info ":warning: cosign signing had partial failures (non-fatal) — images published but some unsigned"
    fi
  else
    log_info "cosign key not configured (mockserver-release/cosign-key) — skipping Docker image signing"
  fi

  # ---- Sync the Docker Hub "Overview" from docker/DOCKERHUB.md --------------
  # Keeps the repo's Docker Hub landing page in sync with version control so it
  # never goes stale (it previously drifted: dead Trello board + Heroku Slack
  # link). STRICTLY non-fatal: needs a Docker Hub token with repo-write scope,
  # which the push/pull token may lack (403 insufficient scope) — skipped with a
  # warning in that case so it never aborts a release.
  sync_dockerhub_description() {
    local desc_file="$REPO_ROOT/docker/DOCKERHUB.md" user token jwt code
    [[ -f "$desc_file" ]] || { log_info "  no docker/DOCKERHUB.md — skipping overview sync"; return 0; }
    user=$(load_secret "mockserver-release/dockerhub" "username") || return 1
    token=$(load_secret "mockserver-release/dockerhub" "token") || return 1
    jwt=$(curl -s --max-time 30 -H "Content-Type: application/json" \
      -d "{\"username\":\"${user}\",\"password\":\"${token}\"}" \
      https://hub.docker.com/v2/users/login/ \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
    [[ -n "$jwt" ]] || { log_info "  Docker Hub API login failed — skipping overview sync"; return 1; }
    code=$(python3 - "$jwt" "$desc_file" <<'PY'
import sys, json, urllib.request, urllib.error
jwt, path = sys.argv[1], sys.argv[2]
body = json.dumps({"full_description": open(path).read()}).encode()
req = urllib.request.Request(
    "https://hub.docker.com/v2/repositories/mockserver/mockserver/",
    data=body, method="PATCH",
    headers={"Content-Type": "application/json", "Authorization": "JWT " + jwt})
try:
    print(urllib.request.urlopen(req, timeout=30).status)
except urllib.error.HTTPError as e:
    print(e.code)
PY
)
    if [[ "$code" == "200" ]]; then
      log_info "  Docker Hub overview updated from docker/DOCKERHUB.md"
    else
      log_info "  WARNING: Docker Hub overview update returned HTTP ${code:-?} (token may lack repo-write scope) — skipped"
      return 1
    fi
  }
  log_info "Sync Docker Hub overview from docker/DOCKERHUB.md"
  sync_dockerhub_description || log_info ":warning: Docker Hub overview not updated (non-fatal)"
fi

log_info "Docker publish complete"
