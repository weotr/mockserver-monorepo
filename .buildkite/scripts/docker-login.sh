#!/usr/bin/env bash
set -euo pipefail

# Docker Hub credentials are split by purpose so the default queue (which runs
# untrusted PR code on every master build) cannot push release-tagged images:
#   - mockserver-build/dockerhub   — SNAPSHOT push (default queue)
#   - mockserver-release/dockerhub — RELEASE push (release queue)
# Release callers (docker-push-release.sh, scripts/release/components/docker.sh)
# export DOCKERHUB_SECRET_ID explicitly; as a fallback, agents on the release
# queue select the release secret automatically.
SECRET_ID="${DOCKERHUB_SECRET_ID:-mockserver-build/dockerhub}"
if [ -z "${DOCKERHUB_SECRET_ID:-}" ] && [ "${BUILDKITE_AGENT_META_DATA_QUEUE:-}" = "release" ]; then
  SECRET_ID="mockserver-release/dockerhub"
fi
REGION="eu-west-2"

echo "--- :aws: Fetching Docker Hub credentials from Secrets Manager"
{ set +x; } 2>/dev/null  # F-BK-04: suppress xtrace before secret fetch
SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "$SECRET_ID" \
  --region "$REGION" \
  --query SecretString \
  --output text)

DOCKER_USERNAME=$(echo "$SECRET_JSON" | jq -r '.username')
DOCKER_TOKEN=$(echo "$SECRET_JSON" | jq -r '.token')

echo "--- :docker: Logging in to Docker Hub"
echo "$DOCKER_TOKEN" | docker login --username "$DOCKER_USERNAME" --password-stdin
