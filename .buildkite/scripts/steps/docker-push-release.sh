#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${RELEASE_TAG:-}" ]]; then
  RELEASE_TAG="${BUILDKITE_TAG:-}"
fi

if [[ -z "$RELEASE_TAG" ]]; then
  echo "Error: RELEASE_TAG environment variable is required (e.g. mockserver-5.15.0)"
  echo "Set it via Buildkite build environment or trigger the build from a git tag."
  exit 1
fi

FULL_TAG="$RELEASE_TAG"
SHORT_TAG="${RELEASE_TAG#mockserver-}"

if [[ "$FULL_TAG" == "$SHORT_TAG" ]]; then
  echo "Error: RELEASE_TAG must start with 'mockserver-' (e.g. mockserver-5.15.0)"
  exit 1
fi

echo "--- :info: Building release image"
echo "Full tag:  mockserver/mockserver:${FULL_TAG}"
echo "Short tag: mockserver/mockserver:${SHORT_TAG}"

.buildkite/scripts/docker-login.sh
.buildkite/scripts/ecr-login.sh

ECR_REPO="public.ecr.aws/t2x9c0i6/mockserver"

DOCKER_CMD="docker buildx build --platform linux/amd64,linux/arm64 --push --tag mockserver/mockserver:${FULL_TAG} --tag mockserver/mockserver:${SHORT_TAG} --tag ${ECR_REPO}:${FULL_TAG} --tag ${ECR_REPO}:${SHORT_TAG} --file docker/Dockerfile ."

echo "┌──────────────────────────────────────────────────────────────────"
echo "│ Docker Command (copy to reproduce locally):"
echo "│"
echo "│   $DOCKER_CMD"
echo "│"
echo "└──────────────────────────────────────────────────────────────────"
echo ""

docker buildx create --use --name multiarch 2>/dev/null || docker buildx use multiarch
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --tag "mockserver/mockserver:${FULL_TAG}" \
  --tag "mockserver/mockserver:${SHORT_TAG}" \
  --tag "${ECR_REPO}:${FULL_TAG}" \
  --tag "${ECR_REPO}:${SHORT_TAG}" \
  --file docker/Dockerfile \
  .

echo "--- :docker: Building and pushing GraalJS variant"
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --tag "mockserver/mockserver:${FULL_TAG}-graaljs" \
  --tag "mockserver/mockserver:${SHORT_TAG}-graaljs" \
  --tag "${ECR_REPO}:${FULL_TAG}-graaljs" \
  --tag "${ECR_REPO}:${SHORT_TAG}-graaljs" \
  --file docker/graaljs/Dockerfile \
  .

echo "--- :docker: Building and pushing clustered image variant"
# Stage the clustered image build context (shaded JAR + Infinispan libs).
# The shaded JAR is already built by the Maven Central step; the Infinispan
# module JAR and its transitive dependencies are resolved from Maven Central.
CLUSTERED_JAR=""
CLUSTERED_JAR=$(find mockserver/mockserver-state-infinispan/target \
  -name 'mockserver-state-infinispan-*.jar' \
  ! -name '*-sources.jar' \
  ! -name '*-javadoc.jar' \
  ! -name '*-tests.jar' \
  -print -quit 2>/dev/null || true)

if [[ -z "$CLUSTERED_JAR" ]]; then
  echo "WARNING: Infinispan module JAR not found — skipping clustered image push"
else
  mkdir -p docker/clustered/libs
  cp "$CLUSTERED_JAR" docker/clustered/libs/
  # Copy the main fat jar
  cp docker/local/mockserver-netty-jar-with-dependencies.jar docker/clustered/ 2>/dev/null \
    || cp "$(find mockserver/mockserver-netty-no-dependencies/target -name 'mockserver-netty-no-dependencies-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*' -print -quit 2>/dev/null)" \
       docker/clustered/mockserver-netty-jar-with-dependencies.jar 2>/dev/null || true

  # Resolve transitive runtime dependencies
  cd mockserver && ./mvnw -pl mockserver-state-infinispan dependency:copy-dependencies \
    -DincludeScope=runtime -DexcludeGroupIds=org.mock-server \
    -DoutputDirectory=../docker/clustered/libs -q && cd ..

  if ! docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --tag "mockserver/mockserver:clustered-${FULL_TAG}" \
    --tag "mockserver/mockserver:clustered-${SHORT_TAG}" \
    --tag "${ECR_REPO}:clustered-${FULL_TAG}" \
    --tag "${ECR_REPO}:clustered-${SHORT_TAG}" \
    docker/clustered; then
    echo "WARNING: clustered image push failed — continuing (main images already published)"
  fi
fi

echo "--- :docker: Building and pushing webhook image"
# Copy the webhook fat jar into the docker/webhook build context.
# The jar is built during the Maven Central step; fall back to local build tree.
WEBHOOK_JAR=""
shopt -s nullglob
for f in mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar; do
  WEBHOOK_JAR="$f"
  break
done
shopt -u nullglob

if [[ -z "$WEBHOOK_JAR" ]]; then
  echo "WARNING: Webhook fat jar not found — skipping webhook image push"
  exit 0
fi

cp "$WEBHOOK_JAR" docker/webhook/mockserver-webhook.jar

# Error-isolated: a webhook push failure must never abort the release —
# the main + GraalJS images have already been published above.
# Push Docker Hub first (primary registry used by Helm chart), then ECR separately.
if ! docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --tag "mockserver/mockserver-webhook:${FULL_TAG}" \
  --tag "mockserver/mockserver-webhook:${SHORT_TAG}" \
  docker/webhook; then
  echo "WARNING: webhook Docker Hub push failed — continuing (main images already published)"
fi

if ! docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --tag "${ECR_REPO}-webhook:${FULL_TAG}" \
  --tag "${ECR_REPO}-webhook:${SHORT_TAG}" \
  docker/webhook; then
  echo "WARNING: webhook ECR push failed — continuing (Docker Hub is the primary registry)"
fi
