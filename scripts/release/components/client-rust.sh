#!/usr/bin/env bash
# Publish mockserver-client crate to crates.io.
#
# Dry-run: update Cargo.toml version + cargo check, skip publish.
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
skip_unless_release_type "client-rust" full,post-maven

log_step "Publish Rust client $RELEASE_VERSION (dry-run=$DRY_RUN)"

CRATE_DIR="$REPO_ROOT/mockserver-client-rust"
CARGO_TOML="$CRATE_DIR/Cargo.toml"

if ! command -v cargo >/dev/null 2>&1; then
  log_info "WARNING: 'cargo' not found on PATH — skipping client-rust publish (non-fatal)"
  exit 0
fi

# Bump version in Cargo.toml
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in Cargo.toml"
  sed -i.bak "s/^version = \".*\"/version = \"${RELEASE_VERSION}\"/" "$CARGO_TOML"
  rm -f "$CARGO_TOML.bak"
fi

# Validate the crate builds
log_info "Validating crate (cargo check)"
(cd "$CRATE_DIR" && cargo check) || {
  log_info "WARNING: cargo check failed — skipping client-rust publish (non-fatal)"
  exit 0
}

if is_dry_run; then
  log_dry "skip: cargo publish to crates.io"
  exit 0
fi

# Check if secret exists
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/crates >/dev/null 2>&1; then
  log_info "WARNING: mockserver-release/crates secret not configured — skipping client-rust publish (non-fatal)"
  exit 0
fi

# Idempotent: check if already published
crates_http=$(curl -s -o /dev/null -w "%{http_code}" \
  "https://crates.io/api/v1/crates/mockserver-client/${RELEASE_VERSION}" 2>/dev/null || echo "000")
if [[ "$crates_http" == "200" ]]; then
  log_info "mockserver-client $RELEASE_VERSION already on crates.io — skipping"
  exit 0
fi

CARGO_TOKEN=$(load_secret "mockserver-release/crates" "token")

log_info "Publishing to crates.io"
(cd "$CRATE_DIR" && CARGO_REGISTRY_TOKEN="$CARGO_TOKEN" cargo publish) || {
  log_info "WARNING: cargo publish failed — skipping (non-fatal)"
  exit 0
}

# Commit version bump
git_commit_and_push "release: publish mockserver-client $RELEASE_VERSION to crates.io" \
  "$CRATE_DIR/Cargo.toml" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "Rust client publish complete"
