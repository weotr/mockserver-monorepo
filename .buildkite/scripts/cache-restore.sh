#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# cache-restore.sh — Restore a dependency cache archive from S3
# ---------------------------------------------------------------------------
# Downloads a tar.gz archive from S3 keyed by a hash of one or more lockfiles,
# then extracts it to the host cache directory used by run-in-docker.sh.
#
# Usage:
#   .buildkite/scripts/cache-restore.sh <cache-name> <lockfile> [<lockfile>...]
#
# Arguments:
#   cache-name   A short label (e.g. "maven", "npm-ui") used as an S3 prefix
#   lockfile     One or more files whose content determines the cache key.
#                If any lockfile doesn't exist, the cache is skipped gracefully.
#
# Environment:
#   BUILDKITE_CI_CACHE_BUCKET  Override the S3 bucket name
#                               (default: mockserver-ci-dependency-cache)
#   BUILDKITE_HOST_CACHE_DIR   Override the host cache base directory
#                               (default: /var/cache/buildkite)
#   BUILDKITE_CI_CACHE_REGION  Override the AWS region (default: eu-west-2)
#
# The script is a no-op (exit 0) on cache miss or any error — builds must
# never fail because the cache is unavailable.
# ---------------------------------------------------------------------------
set -euo pipefail

CACHE_NAME="${1:?Usage: cache-restore.sh <cache-name> <lockfile> [<lockfile>...]}"
shift
LOCKFILES=("$@")

BUCKET="${BUILDKITE_CI_CACHE_BUCKET:-mockserver-ci-dependency-cache}"
REGION="${BUILDKITE_CI_CACHE_REGION:-eu-west-2}"
HOST_CACHE_BASE="${BUILDKITE_HOST_CACHE_DIR:-/var/cache/buildkite}"

# Compute cache key from lockfile contents
compute_cache_key() {
  local hash_input=""
  for lockfile in "${LOCKFILES[@]}"; do
    if [[ ! -f "$lockfile" ]]; then
      echo "  Cache key file not found: $lockfile — skipping cache restore"
      return 1
    fi
    hash_input+="$(sha256sum "$lockfile" | cut -d' ' -f1)"
  done
  echo "$hash_input" | sha256sum | cut -d' ' -f1
}

echo "~~~ :s3: Restoring ${CACHE_NAME} dependency cache"

CACHE_KEY=$(compute_cache_key) || exit 0
S3_KEY="${CACHE_NAME}/${CACHE_KEY}.tar.gz"

echo "  Cache key: ${CACHE_KEY:0:12}..."
echo "  S3 path:   s3://${BUCKET}/${S3_KEY}"

# Check if the cache object exists before downloading
if ! aws s3api head-object \
    --bucket "$BUCKET" \
    --key "$S3_KEY" \
    --region "$REGION" \
    >/dev/null 2>&1; then
  echo "  Cache MISS — no cached archive found"
  exit 0
fi

# Download and extract
TMPFILE=$(mktemp /tmp/cache-restore-XXXXXX.tar.gz)
trap 'rm -f "$TMPFILE"' EXIT

if aws s3 cp "s3://${BUCKET}/${S3_KEY}" "$TMPFILE" \
    --region "$REGION" \
    --quiet 2>/dev/null; then
  mkdir -p "$HOST_CACHE_BASE"
  tar xzf "$TMPFILE" -C "$HOST_CACHE_BASE"
  echo "  Cache HIT — restored to ${HOST_CACHE_BASE}/"
else
  echo "  Cache restore failed (non-fatal) — building from scratch"
fi
