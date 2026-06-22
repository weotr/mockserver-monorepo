#!/usr/bin/env bash
# Operator halt check (kill-switch). See .opencode/rules/operator-halt.md
#
# Usage: check-halt.sh [scope]
#   Always checks the GLOBAL sentinel AGENT_HALT.
#   If [scope] is given, also checks the scoped sentinel AGENT_HALT_<scope>
#   (e.g. AGENT_HALT_commit, AGENT_HALT_release) so an operator can pause a
#   subset of activity.
#
# Exit 0 = clear to proceed. Exit 1 = a halt is engaged. The sentinels live at
# the repository's main-checkout root, resolved via the shared git common dir, so
# the halt is GLOBAL across all worktrees and sessions. A detection is appended to
# <main-root>/.tmp/halt-audit.log for an auditable record.
set -uo pipefail

scope="${1:-}"

common="$(git rev-parse --git-common-dir 2>/dev/null)" || { echo "not a git repo — cannot check halt"; exit 0; }
case "$common" in
  /*) ;;                       # already absolute
  *) common="$(pwd)/$common" ;;
esac
main_root="$(cd "$(dirname "$common")" && pwd)"

sentinels=("$main_root/AGENT_HALT")
[ -n "$scope" ] && sentinels+=("$main_root/AGENT_HALT_$scope")

for halt in "${sentinels[@]}"; do
  [ -f "$halt" ] || continue
  echo "OPERATOR HALT ENGAGED — $halt"
  echo "AI SDLC activity is paused. Do NOT start new actions, commits, pushes, or"
  echo "external/irreversible work. Do NOT delete this sentinel without explicit"
  echo "user direction. Reason (if given):"
  echo "----"
  cat "$halt" 2>/dev/null || true
  # auditable record (best-effort; never fail the check on logging error)
  mkdir -p "$main_root/.tmp" 2>/dev/null || true
  printf '%s halt detected (pid %s, cwd %s, sentinel %s)\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$$" "$(pwd)" "$halt" \
    >> "$main_root/.tmp/halt-audit.log" 2>/dev/null || true
  exit 1
done
exit 0
