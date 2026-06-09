#!/usr/bin/env bash
# Publish Testcontainers.MockServer to NuGet.
#
# Dry-run: build + pack, skip push.
# SOFT: if the secret is absent or the publish fails, log a warning and exit 0.

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

require_release_inputs
skip_unless_release_type "tc-dotnet" full,post-maven

log_step "Publish Testcontainers.MockServer $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-testcontainers/dotnet"
BUILD_PROPS="$COMPONENT_DIR/Directory.Build.props"

if ! command -v dotnet >/dev/null 2>&1; then
  log_info "WARNING: 'dotnet' not found on PATH — skipping tc-dotnet publish (non-fatal)"
  exit 0
fi

# Bump version in Directory.Build.props
if ! is_dry_run; then
  log_info "Updating MockServerVersion to $RELEASE_VERSION in Directory.Build.props"
  sed -i.bak "s|<MockServerVersion>.*</MockServerVersion>|<MockServerVersion>${RELEASE_VERSION}</MockServerVersion>|" "$BUILD_PROPS"
  rm -f "$BUILD_PROPS.bak"
fi

# Build and pack
log_info "Building and packing"
(cd "$COMPONENT_DIR" && \
  dotnet restore && \
  dotnet build -c Release --no-restore && \
  dotnet pack src/Testcontainers.MockServer/Testcontainers.MockServer.csproj \
    -c Release --no-build -o ./artifacts) || {
  log_info "WARNING: dotnet build/pack failed — skipping tc-dotnet publish (non-fatal)"
  exit 0
}

if is_dry_run; then
  log_dry "skip: dotnet nuget push to NuGet.org"
  log_info "Built artifacts:"
  ls -la "$COMPONENT_DIR/artifacts/" 2>/dev/null || true
  exit 0
fi

# Check if secret exists
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/nuget >/dev/null 2>&1; then
  log_info "WARNING: mockserver-release/nuget secret not configured — skipping tc-dotnet publish (non-fatal)"
  exit 0
fi

# Idempotent: check if already published
http_code=$(curl -s -o /dev/null -w "%{http_code}" \
  "https://api.nuget.org/v3-flatcontainer/testcontainers.mockserver/${RELEASE_VERSION}/testcontainers.mockserver.${RELEASE_VERSION}.nupkg" 2>/dev/null || echo "000")
if [[ "$http_code" == "200" ]]; then
  log_info "Testcontainers.MockServer $RELEASE_VERSION already on NuGet — skipping"
  exit 0
fi

NUGET_API_KEY=$(load_secret "mockserver-release/nuget" "api_key")

log_info "Pushing to NuGet.org"
dotnet nuget push "$COMPONENT_DIR/artifacts/Testcontainers.MockServer.${RELEASE_VERSION}.nupkg" \
  --api-key "$NUGET_API_KEY" \
  --source https://api.nuget.org/v3/index.json \
  --skip-duplicate || {
  log_info "WARNING: dotnet nuget push failed — skipping (non-fatal)"
  exit 0
}

# Commit version bump
git_commit_and_push "release: publish Testcontainers.MockServer $RELEASE_VERSION to NuGet" \
  "$BUILD_PROPS" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "Testcontainers.MockServer publish complete"
