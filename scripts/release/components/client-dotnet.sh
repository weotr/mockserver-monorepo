#!/usr/bin/env bash
# Publish MockServer.Client to NuGet.
#
# Dry-run: build + pack, skip push to NuGet.
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
skip_unless_release_type "client-dotnet" full,post-maven

log_step "Publish .NET client $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-client-dotnet"
CSPROJ="$COMPONENT_DIR/src/MockServer.Client/MockServer.Client.csproj"

if ! command -v dotnet >/dev/null 2>&1; then
  log_info "WARNING: 'dotnet' not found on PATH — skipping client-dotnet publish (non-fatal)"
  exit 0
fi

# Bump version in .csproj
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in MockServer.Client.csproj"
  sed -i.bak "s|<Version>.*</Version>|<Version>${RELEASE_VERSION}</Version>|" "$CSPROJ"
  rm -f "$CSPROJ.bak"
fi

# Build and pack
log_info "Building and packing"
(cd "$COMPONENT_DIR" && dotnet pack src/MockServer.Client/MockServer.Client.csproj -c Release -o ./artifacts) || {
  log_info "WARNING: dotnet pack failed — skipping client-dotnet publish (non-fatal)"
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
  log_info "WARNING: mockserver-release/nuget secret not configured — skipping client-dotnet publish (non-fatal)"
  exit 0
fi

# Idempotent: check if already published
http_code=$(curl -s -o /dev/null -w "%{http_code}" \
  "https://api.nuget.org/v3-flatcontainer/mockserver.client/${RELEASE_VERSION}/mockserver.client.${RELEASE_VERSION}.nupkg" 2>/dev/null || echo "000")
if [[ "$http_code" == "200" ]]; then
  log_info "MockServer.Client $RELEASE_VERSION already on NuGet — skipping"
  exit 0
fi

NUGET_API_KEY=$(load_secret "mockserver-release/nuget" "api_key")

log_info "Pushing to NuGet.org"
dotnet nuget push "$COMPONENT_DIR/artifacts/MockServer.Client.${RELEASE_VERSION}.nupkg" \
  --api-key "$NUGET_API_KEY" \
  --source https://api.nuget.org/v3/index.json \
  --skip-duplicate || {
  log_info "WARNING: dotnet nuget push failed — skipping (non-fatal)"
  exit 0
}

# Commit version bump
git_commit_and_push "release: publish MockServer.Client $RELEASE_VERSION to NuGet" \
  "$COMPONENT_DIR/src/MockServer.Client/MockServer.Client.csproj" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info ".NET client publish complete"
