#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# cache-save.sh — Save a dependency cache archive to S3
# ---------------------------------------------------------------------------
# Archives the host cache directories populated by run-in-docker.sh volume
# mounts and uploads the tar.gz to S3, keyed by a hash of lockfiles.
#
# Usage:
#   .buildkite/scripts/cache-save.sh <cache-name> <dir> [<dir>...] -- <lockfile> [<lockfile>...]
#
# Arguments:
#   cache-name   A short label (e.g. "maven", "npm-ui") used as an S3 prefix
#   dir          One or more directories (relative to HOST_CACHE_BASE) to archive
#   --           Separator between directories and lockfiles
#   lockfile     One or more files whose content determines the cache key
#
# Environment:
#   BUILDKITE_CI_CACHE_BUCKET  Override the S3 bucket name
#                               (default: mockserver-ci-dependency-cache)
#   BUILDKITE_HOST_CACHE_DIR   Override the host cache base directory
#                               (default: /var/cache/buildkite)
#   BUILDKITE_CI_CACHE_REGION  Override the AWS region (default: eu-west-2)
#
# The script is a no-op (exit 0) on any error — builds must never fail
# because of a cache save failure.
# ---------------------------------------------------------------------------
set -euo pipefail

CACHE_NAME="${1:?Usage: cache-save.sh <cache-name> <dir> [<dir>...] -- <lockfile> [<lockfile>...]}"
shift

DIRS=()
LOCKFILES=()
PARSING_DIRS=true

for arg in "$@"; do
  if [[ "$arg" == "--" ]]; then
    PARSING_DIRS=false
    continue
  fi
  if $PARSING_DIRS; then
    DIRS+=("$arg")
  else
    LOCKFILES+=("$arg")
  fi
done

if [[ ${#DIRS[@]} -eq 0 || ${#LOCKFILES[@]} -eq 0 ]]; then
  echo "Error: must specify at least one directory and one lockfile separated by --"
  echo "Usage: cache-save.sh <cache-name> <dir> [<dir>...] -- <lockfile> [<lockfile>...]"
  exit 0  # non-fatal
fi

BUCKET="${BUILDKITE_CI_CACHE_BUCKET:-mockserver-ci-dependency-cache}"
REGION="${BUILDKITE_CI_CACHE_REGION:-eu-west-2}"
HOST_CACHE_BASE="${BUILDKITE_HOST_CACHE_DIR:-/var/cache/buildkite}"

# Compute cache key from lockfile contents
compute_cache_key() {
  local hash_input=""
  for lockfile in "${LOCKFILES[@]}"; do
    if [[ ! -f "$lockfile" ]]; then
      echo "  Cache key file not found: $lockfile — skipping cache save"
      return 1
    fi
    hash_input+="$(sha256sum "$lockfile" | cut -d' ' -f1)"
  done
  echo "$hash_input" | sha256sum | cut -d' ' -f1
}

echo "~~~ :s3: Saving ${CACHE_NAME} dependency cache"

CACHE_KEY=$(compute_cache_key) || exit 0
S3_KEY="${CACHE_NAME}/${CACHE_KEY}.tar.gz"

echo "  Cache key: ${CACHE_KEY:0:12}..."
echo "  S3 path:   s3://${BUCKET}/${S3_KEY}"

# Skip if cache already exists (saves upload time + bandwidth)
if aws s3api head-object \
    --bucket "$BUCKET" \
    --key "$S3_KEY" \
    --region "$REGION" \
    >/dev/null 2>&1; then
  echo "  Cache already exists — skipping upload"
  exit 0
fi

# Verify at least one directory has content
HAS_CONTENT=false
for dir in "${DIRS[@]}"; do
  FULL_DIR="${HOST_CACHE_BASE}/${dir}"
  if [[ -d "$FULL_DIR" ]] && [[ -n "$(ls -A "$FULL_DIR" 2>/dev/null)" ]]; then
    HAS_CONTENT=true
    break
  fi
done

if ! $HAS_CONTENT; then
  echo "  No cache content to save — skipping"
  exit 0
fi

# Create the archive
TMPFILE=$(mktemp /tmp/cache-save-XXXXXX.tar.gz)
trap 'rm -f "$TMPFILE"' EXIT

TAR_ARGS=()
for dir in "${DIRS[@]}"; do
  FULL_DIR="${HOST_CACHE_BASE}/${dir}"
  if [[ -d "$FULL_DIR" ]]; then
    TAR_ARGS+=("$dir")
  fi
done

if tar czf "$TMPFILE" -C "$HOST_CACHE_BASE" "${TAR_ARGS[@]}" 2>/dev/null; then
  SIZE=$(du -h "$TMPFILE" | cut -f1)
  echo "  Archive size: ${SIZE}"

  if aws s3 cp "$TMPFILE" "s3://${BUCKET}/${S3_KEY}" \
      --region "$REGION" \
      --quiet 2>/dev/null; then
    echo "  Cache saved successfully"
  else
    echo "  Cache upload failed (non-fatal)"
  fi
else
  echo "  Cache archive creation failed (non-fatal)"
fi
