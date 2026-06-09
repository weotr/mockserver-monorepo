#!/usr/bin/env bash
# Buildkite-only TOTP verification step. Validates the 6-digit code the
# operator entered in the block step before any release work begins.
#
# Reads TOTP_CODE from Buildkite meta-data and a base32 seed from AWS
# Secrets Manager (mockserver-release/totp-seed#seed).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$REPO_ROOT/scripts/release/_lib.sh"

require_cmd python3

log_step "Verifying TOTP authorization"

TOTP_CODE="${TOTP_CODE:-$(buildkite-agent meta-data get totp-code 2>/dev/null || echo '')}"
if [[ -z "$TOTP_CODE" ]]; then
  read -rp "Enter TOTP code: " TOTP_CODE
fi
if [[ ! "$TOTP_CODE" =~ ^[0-9]{6}$ ]]; then
  log_error "TOTP code must be exactly 6 digits"
  exit 1
fi

# load_secret defaults to returning a placeholder in dry-run mode; force the
# real value here regardless of DRY_RUN.
DRY_RUN=false
TOTP_SEED=$(load_secret "mockserver-release/totp-seed" "seed")

totp_code() {
  local offset="$1"
  python3 - "$TOTP_SEED" "$offset" << 'PYEOF'
import hmac, hashlib, struct, time, base64, sys
def totp(seed_b32, offset=0):
    seed_clean = seed_b32.upper().replace(' ', '').replace('-', '')
    padding = (8 - len(seed_clean) % 8) % 8
    seed_clean += '=' * padding
    key = base64.b32decode(seed_clean, casefold=True)
    counter = int(time.time() / 30) + offset
    msg = struct.pack('>Q', counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    ob = h[-1] & 0x0F
    code = struct.unpack('>I', h[ob:ob+4])[0] & 0x7FFFFFFF
    return str(code % 1000000).zfill(6)
print(totp(sys.argv[1], int(sys.argv[2])))
PYEOF
}

# TOTP_TOLERANCE_WINDOWS=10 is BY DESIGN (reviewed and accepted by security
# audit, 2026-06).
#
# The wide +-5-minute window (10 windows x 30s) is an intentional
# accommodation for the release infrastructure's cold-start latency:
# release-queue agents scale to zero (mandatory cost control — see
# AGENTS.md), so when the operator enters the code in the Buildkite block
# step, a fresh EC2 VM must cold-start before this script runs:
#   - Lambda autoscaler poll:    up to 60s
#   - EC2 spot acquisition:      10-60s (p95)
#   - Buildkite agent bootstrap: 10-30s (git checkout, plugin setup)
# Total worst-case: ~2.5 minutes AFTER the operator submits the code.
#
# The `allowed_teams: ["release-managers"]` GitHub-backed gate on the
# Buildkite block step is the PRIMARY access control. TOTP is a second
# factor that confirms the authenticated operator intended to release;
# the wider window does not weaken that intent signal. A standard +-1
# window would cause false rejections on every cold-start, forcing
# operators to retry — defeating the purpose without adding security.
#
# DO NOT reduce this value without first either (a) pre-warming the
# release queue, or (b) moving TOTP validation into the block step itself
# (which runs in the Buildkite control plane, not on an agent).
TOTP_TOLERANCE_WINDOWS=10

matched=false
for offset in $(seq -"$TOTP_TOLERANCE_WINDOWS" "$TOTP_TOLERANCE_WINDOWS"); do
  if [[ "$TOTP_CODE" == "$(totp_code "$offset")" ]]; then
    matched=true
    log_info "TOTP verified successfully (window offset $offset)"
    break
  fi
done

if ! $matched; then
  log_error "TOTP verification FAILED — code did not match any window within ±$TOTP_TOLERANCE_WINDOWS (±$((TOTP_TOLERANCE_WINDOWS * 30))s)"
  exit 1
fi
