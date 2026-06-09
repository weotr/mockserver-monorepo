#!/usr/bin/env bash
# Publish testcontainers-mockserver to PyPI.
#
# Dry-run: build + twine check, skip upload.
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
skip_unless_release_type "tc-python" full,post-maven

log_step "Publish testcontainers-mockserver (Python) $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-testcontainers/python"
PYPROJECT="$COMPONENT_DIR/pyproject.toml"

if ! command -v python3 >/dev/null 2>&1; then
  log_info "WARNING: 'python3' not found on PATH — skipping tc-python publish (non-fatal)"
  exit 0
fi

# Bump version in pyproject.toml
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in pyproject.toml"
  sed -i.bak "s/^version = \".*\"/version = \"${RELEASE_VERSION}\"/" "$PYPROJECT"
  rm -f "$PYPROJECT.bak"
fi

# Validate version matches
VERSION=$(grep -E '^version\s*=' "$PYPROJECT" | head -1 | sed 's/.*= *"\(.*\)".*/\1/')
if [[ -z "$VERSION" ]]; then
  log_info "WARNING: could not parse version from pyproject.toml — skipping tc-python publish (non-fatal)"
  exit 0
fi

# Build
log_info "Building distribution"
rm -rf "$COMPONENT_DIR/dist" "$COMPONENT_DIR/build" "$COMPONENT_DIR"/*.egg-info 2>/dev/null || true
(cd "$COMPONENT_DIR" && python3 -m build .) 2>/dev/null || {
  # Try with pip-installed build module
  pip3 install --quiet build 2>/dev/null || true
  (cd "$COMPONENT_DIR" && python3 -m build .) || {
    log_info "WARNING: python build failed — skipping tc-python publish (non-fatal)"
    exit 0
  }
}

# Validate with twine
if command -v twine >/dev/null 2>&1 || pip3 install --quiet twine 2>/dev/null; then
  (cd "$COMPONENT_DIR" && python3 -m twine check dist/*) || {
    log_info "WARNING: twine check failed — skipping tc-python publish (non-fatal)"
    exit 0
  }
fi

if is_dry_run; then
  log_dry "skip: twine upload to PyPI"
  log_info "Built artifacts:"
  ls -la "$COMPONENT_DIR/dist/" 2>/dev/null || true
  exit 0
fi

# Idempotent: check if already published
http_code=$(curl -s -o /dev/null -w "%{http_code}" \
  "https://pypi.org/pypi/testcontainers-mockserver/$RELEASE_VERSION/json" 2>/dev/null || echo "000")
if [[ "$http_code" == "200" ]]; then
  log_info "testcontainers-mockserver $RELEASE_VERSION already on PyPI — skipping"
  exit 0
fi

# Reuses the same PyPI token as pypi.sh. Load directly (GetSecretValue) rather
# than gating on describe-secret: the release agent has GetSecretValue but not
# DescribeSecret on this secret, so a describe gate would misfire (AccessDenied
# read as "absent") and skip publishing. A load failure soft-skips.
PYPI_TOKEN=$(load_secret "mockserver-build/pypi" "token") || {
  log_info "WARNING: could not read mockserver-build/pypi — skipping tc-python publish (non-fatal)"
  exit 0
}

log_info "Uploading to PyPI"
TWINE_USERNAME=__token__ TWINE_PASSWORD="$PYPI_TOKEN" \
  python3 -m twine upload "$COMPONENT_DIR/dist/"* || {
  log_info "WARNING: twine upload failed — skipping (non-fatal)"
  exit 0
}

# Commit version bump
git_commit_and_push "release: publish testcontainers-mockserver $RELEASE_VERSION to PyPI" \
  "$PYPROJECT" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "testcontainers-mockserver (Python) publish complete"
