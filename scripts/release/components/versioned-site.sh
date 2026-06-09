#!/usr/bin/env bash
# Read and persist the current main website's terraform outputs (always),
# and additionally create a new versioned subdomain (X-Y.mock-server.com)
# for major/minor releases when CREATE_VERSIONED_SITE=yes.
#
# Why this script runs on every release (not only major/minor): downstream
# components (helm, javadoc, website, schema) all consume WEBSITE_BUCKET
# and DISTRIBUTION_ID from .tmp/release-outputs.env. Before this script
# was unconditionalised, a patch release exited at the CREATE_VERSIONED_SITE
# guard before calling set_release_output, leaving those env vars empty
# for every downstream component on patch releases — `website.sh` would
# fall through its CloudFront-invalidation guard silently, and the other
# components would fail loudly with "WEBSITE_BUCKET not set". F-VS-01
# in the audit report.
#
# Dry-run: terraform calls are skipped entirely; the rest of the bash
# logic is validated.

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
require_cmd aws
require_release_inputs
skip_unless_release_type "versioned-site" full,post-maven

log_step "Versioned-site setup for $RELEASE_VERSION (dry-run=$DRY_RUN, create-new=$CREATE_VERSIONED_SITE)"
sync_to_origin_master

# Run terraform in a container with materialised AWS creds (so the container
# doesn't need access to the EC2 metadata endpoint).
tf() {
  local aws_env
  aws_env=$(aws configure export-credentials --format env 2>/dev/null || true)
  if [[ -z "$aws_env" ]]; then
    log_error "aws configure export-credentials returned no creds (requires v2.10+)"
    exit 1
  fi
  local -a env_args=()
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    line="${line#export }"
    env_args+=(-e "$line")
  done <<< "$aws_env"
  env_args+=(-e "AWS_DEFAULT_REGION=${AWS_REGION:-eu-west-2}")
  env_args+=(-e "AWS_REGION=${AWS_REGION:-eu-west-2}")
  # The provider assumes the website-account role; the backend stays on the
  # build-account creds materialised above.
  env_args+=(-e "TF_VAR_website_role_arn=${WEBSITE_ROLE_ARN:-}")
  env_args+=(-e "TF_VAR_role_external_id=${ROLE_EXTERNAL_ID:-}")
  in_docker "$TERRAFORM_IMAGE" -w /build "${env_args[@]}" -- "$@"
}

TF_DIR="$REPO_ROOT/terraform/website"
TFVARS="$TF_DIR/terraform.tfvars"
[[ -f "$TFVARS" ]] || { log_error "terraform.tfvars not found"; exit 1; }

if is_dry_run; then
  # We deliberately skip all terraform calls in dry-run:
  #   - they require website-account creds that the release role can fetch
  #     but only at apply-time (read perms are intentionally narrow);
  #   - they touch live remote state (S3 backend), a coupling we don't want
  #     a "dry-run" smoke test to have.
  # Downstream components also run in dry-run, where they skip their actual
  # upload steps, so they don't need WEBSITE_BUCKET/DISTRIBUTION_ID populated.
  if [[ "$CREATE_VERSIONED_SITE" == "yes" ]]; then
    SUBDOMAIN=$(version_to_subdomain "$RELEASE_VERSION")
    log_dry "would: add ${SUBDOMAIN} entry to terraform.tfvars"
    log_dry "would: set latest_version to ${SUBDOMAIN}"
    log_dry "skip: terraform init+plan+apply + S3 mirror + commit"
  else
    log_info "Dry-run (CREATE_VERSIONED_SITE=$CREATE_VERSIONED_SITE) — nothing to validate"
  fi
  exit 0
fi

# Terraform's S3 backend lives in the build account, so terraform runs with
# the agent's own (build-account) credentials. Website-account resources are
# reached via the provider's assume_role, fed the release-website role ARN
# through TF_VAR_website_role_arn (see the tf() helper).
WEBSITE_ROLE_ARN=$(load_secret "mockserver-release/website-role" "role_arn") \
  || { log_error "Failed to load mockserver-release/website-role ARN from Secrets Manager"; exit 1; }
ROLE_EXTERNAL_ID=$(load_secret "mockserver-release/website-role" "external_id" 2>/dev/null || true)

# backend.tf hard-codes `profile = "mockserver-build"` for human use. The
# container has no AWS profile configured (it uses env-var creds the
# tf() helper materialises), so override profile= at init time. -reconfigure
# is harmless on a fresh checkout. We always init (even on patch releases)
# because the subsequent `terraform output` call needs an initialised state.
tf -chdir=/build/terraform/website init -input=false -reconfigure -backend-config=profile=

SUBDOMAIN=""
OLD_BUCKET=""
if [[ "$CREATE_VERSIONED_SITE" == "yes" ]]; then
  SUBDOMAIN=$(version_to_subdomain "$RELEASE_VERSION")
  log_info "Creating versioned site: ${SUBDOMAIN}.mock-server.com"

  if ! grep -qE "\"${SUBDOMAIN}\"\\s*=" "$TFVARS"; then
    BUCKET_NAME="aws-website-mockserver-${SUBDOMAIN}"
    log_info "Adding ${SUBDOMAIN} (bucket: $BUCKET_NAME) to terraform.tfvars"
    sed -i.bak "s/^}$/  \"${SUBDOMAIN}\" = { bucket_name = \"${BUCKET_NAME}\" }\n}/" "$TFVARS"
    rm -f "$TFVARS.bak"
  fi

  OLD_SUBDOMAIN=$(version_to_subdomain "$OLD_VERSION")
  OLD_BUCKET=$(grep -E "\"${OLD_SUBDOMAIN}\"\\s*=" "$TFVARS" 2>/dev/null | sed 's/.*bucket_name *= *"\([^"]*\)".*/\1/' || true)

  sed -i.bak "s/^latest_version.*=.*/latest_version              = \"${SUBDOMAIN}\"/" "$TFVARS"
  rm -f "$TFVARS.bak"

  trap 'rm -f "$TF_DIR/tfplan"' EXIT
  tf -chdir=/build/terraform/website plan -input=false -out=tfplan
  tf -chdir=/build/terraform/website apply -input=false tfplan
  rm -f "$TF_DIR/tfplan"
  trap - EXIT
else
  log_info "Skipping new-site creation (CREATE_VERSIONED_SITE=$CREATE_VERSIONED_SITE) — reading current main outputs to seed downstream WEBSITE_BUCKET / DISTRIBUTION_ID"
fi

# Always read the current main outputs. On a major/minor release these
# reflect the just-applied infrastructure; on a patch release they reflect
# the pre-existing main bucket + distribution, which is exactly what
# downstream components should target.
NEW_BUCKET=$(tf -chdir=/build/terraform/website output -raw main_bucket_name)
NEW_DISTRIBUTION_ID=$(tf -chdir=/build/terraform/website output -raw main_distribution_id)
[[ -n "$NEW_BUCKET" ]] || { log_error "terraform output main_bucket_name returned empty"; exit 1; }
[[ -n "$NEW_DISTRIBUTION_ID" ]] || { log_error "terraform output main_distribution_id returned empty"; exit 1; }
log_info "Main bucket: $NEW_BUCKET"
log_info "Main distribution: $NEW_DISTRIBUTION_ID"

if [[ "$CREATE_VERSIONED_SITE" == "yes" && -n "$OLD_BUCKET" && "$OLD_BUCKET" != "$NEW_BUCKET" ]]; then
  log_info "Mirror content $OLD_BUCKET -> $NEW_BUCKET"
  # Subshell: assume_website_role exports website-account credentials. They
  # must not leak into git_commit_and_push below, which reads the GitHub token
  # from a build-account secret and so needs the agent's own credentials.
  (
    assume_website_role
    aws s3 sync "s3://$OLD_BUCKET/" "s3://$NEW_BUCKET/"
  )
fi

# Persist for downstream components (helm, javadoc, website, schema). The CI
# adapter syncs .tmp/release-outputs.env to whatever cross-step state the
# pipeline system provides (Buildkite meta-data, GitHub Actions outputs, etc.).
set_release_output WEBSITE_BUCKET "$NEW_BUCKET"
set_release_output DISTRIBUTION_ID "$NEW_DISTRIBUTION_ID"

if [[ "$CREATE_VERSIONED_SITE" == "yes" ]]; then
  git_commit_and_push "release: versioned site ${SUBDOMAIN}.mock-server.com" \
    "terraform/website/terraform.tfvars"
  log_info "Versioned site ${SUBDOMAIN}.mock-server.com created"
else
  log_info "Versioned-site outputs published to release-outputs.env (no new site created for this release type)"
fi
