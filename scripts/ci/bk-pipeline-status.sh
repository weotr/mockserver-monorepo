#!/usr/bin/env bash
#
# bk-pipeline-status.sh — report (and optionally watch) the status of a Buildkite
# pipeline build, without re-deriving the polling/parsing each time.
#
# Uses the locally-authenticated `bk` CLI's `bk build list` / `bk job log`
# (NOT `bk auth token` + curl to the REST API, and NOT the AWS Secrets Manager
# tokens): only the local `bk` CLI reliably has build-state + read-build-logs
# scope here, and it does not depend on an AWS SSO session. See
# docs/infrastructure/ci-cd.md.
#
# Designed to be driven by the agent Monitor tool in --watch mode: it prints one
# concise line per state change and exits when the target build's job reaches a
# terminal state (exit 0 = passed, 2 = failed/broken/canceled, 3 = timed out).
#
# Usage:
#   scripts/ci/bk-pipeline-status.sh [options]
#
# Options:
#   -p, --pipeline NAME   Buildkite pipeline slug (default: mockserver-java)
#   -c, --commit SHA      target the build whose commit starts with SHA
#                         (default: the most recent build)
#   -b, --build N         target a specific build number (overrides --commit)
#   -j, --job NAME        job to report state/exit for (default: ":maven: build")
#   -w, --watch           poll until the target job (or build) is terminal,
#                         printing one line per state change
#   -i, --interval SECS   poll interval for --watch (default: 90)
#   -t, --timeout SECS    give up watching after this long (default: 3600)
#   -l, --logs            print the failing (or --job) job's log and exit —
#                         tailed to --lines, or filtered to --grep if given
#   -n, --lines N         log tail length for --logs (default: 60)
#   -g, --grep PATTERN    with --logs, search the WHOLE log for PATTERN
#                         (case-insensitive ERE) instead of tailing — use this to
#                         find the failure (e.g. 'FAILURE|<<< ERROR|npm error')
#   --json                print the matched build's raw JSON and exit
#   -h, --help            show this help
#
# Examples:
#   scripts/ci/bk-pipeline-status.sh -p mockserver-java -c adc9ceaa6
#   scripts/ci/bk-pipeline-status.sh -p mockserver-java -c adc9ceaa6 --watch
#   scripts/ci/bk-pipeline-status.sh -p mockserver-java -b 1160 --logs
#
set -euo pipefail

PIPELINE="mockserver-java"
COMMIT=""
BUILD=""
JOB=":maven: build"
WATCH=0
INTERVAL=90
TIMEOUT=3600
LOGS=0
LINES=60
GREP=""
JSON=0

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    -p|--pipeline) PIPELINE="$2"; shift 2;;
    -c|--commit)   COMMIT="$2"; shift 2;;
    -b|--build)    BUILD="$2"; shift 2;;
    -j|--job)      JOB="$2"; shift 2;;
    -w|--watch)    WATCH=1; shift;;
    -i|--interval) INTERVAL="$2"; shift 2;;
    -t|--timeout)  TIMEOUT="$2"; shift 2;;
    -l|--logs)     LOGS=1; shift;;
    -n|--lines)    LINES="$2"; shift 2;;
    -g|--grep)     GREP="$2"; LOGS=1; shift 2;;
    --json)        JSON=1; shift;;
    -h|--help)     usage 0;;
    *) echo "unknown option: $1" >&2; usage 1;;
  esac
done

command -v bk >/dev/null 2>&1 || { echo "FAIL: bk CLI not installed (brew install buildkite/buildkite/bk)" >&2; exit 4; }
command -v python3 >/dev/null 2>&1 || { echo "FAIL: python3 not found" >&2; exit 4; }
export JOB  # read by the awk in print_line via ENVIRON["JOB"]

# Fetch the build list once and select the target build as a compact summary line:
#   build#<n>\t<commit>\t<buildState>\t<jobState>\t<jobExit>\t<jobId>
# Emits "poll-error" if the listing can't be parsed (transient), or "no-build"
# if no matching build exists yet.
fetch_status() {
  bk build list --pipeline "$PIPELINE" 2>/dev/null | BUILD="$BUILD" COMMIT="$COMMIT" JOB="$JOB" python3 -c '
import sys, json, os
try:
    data = json.load(sys.stdin)
except Exception:
    print("poll-error"); sys.exit()
rows = data if isinstance(data, list) else [data]
build_n = os.environ.get("BUILD", "")
commit = os.environ.get("COMMIT", "")
job_name = os.environ.get("JOB", "")
target = None
if build_n:
    target = next((b for b in rows if str(b.get("number")) == str(build_n)), None)
elif commit:
    target = next((b for b in rows if str(b.get("commit", "")).startswith(commit)), None)
elif rows:
    target = rows[0]
if not target:
    newest = rows[0] if rows else {}
    print("no-build\t%s\t%s\t-\t-\t-" % (newest.get("number", "?"), str(newest.get("commit", ""))[:9]))
    sys.exit()
job = next((j for j in target.get("jobs", []) if j.get("name") == job_name), None)
print("build#%s\t%s\t%s\t%s\t%s\t%s" % (
    target.get("number", "?"),
    str(target.get("commit", ""))[:9],
    target.get("state", "?"),
    (job.get("state") if job else "n/a"),
    (job.get("exit_status") if job and job.get("exit_status") is not None else "-"),
    (job.get("id") if job else "-"),
))
'
}

# Print the log tail of the failing (or --job) job for the target build.
print_logs() {
  local line job_id job_state build_n
  line="$(fetch_status)"
  build_n="$(printf '%s' "$line" | cut -f1 | sed 's/build#//')"
  job_id="$(printf '%s' "$line" | cut -f6)"
  job_state="$(printf '%s' "$line" | cut -f4)"
  if [ -z "$job_id" ] || [ "$job_id" = "-" ]; then
    # fall back to the first failed/broken script job on the build
    job_id="$(bk build list --pipeline "$PIPELINE" 2>/dev/null | BUILD="$build_n" python3 -c '
import sys, json, os
data = json.load(sys.stdin); rows = data if isinstance(data, list) else [data]
b = next((x for x in rows if str(x.get("number")) == os.environ.get("BUILD")), None)
if b:
    j = next((j for j in b.get("jobs", []) if j.get("type") == "script" and j.get("state") in ("failed","broken")), None)
    print(j.get("id") if j else "")
')"
  fi
  if [ -z "$job_id" ] || [ "$job_id" = "-" ]; then
    echo "no failing job found for build#${build_n} (job state: ${job_state})" >&2
    return 2
  fi
  if [ -n "$GREP" ]; then
    echo "==== ${PIPELINE} build#${build_n} job log — lines matching /${GREP}/ ===="
    bk job log "$job_id" 2>/dev/null \
      | sed 's/\x1b\[[0-9;]*m//g; s/_bk;t=[0-9]*//g; s/\r//g' \
      | grep -niE "$GREP" || echo "(no lines matched /${GREP}/)"
  else
    echo "==== ${PIPELINE} build#${build_n} job log tail (${LINES} lines) ===="
    bk job log "$job_id" 2>/dev/null \
      | sed 's/\x1b\[[0-9;]*m//g; s/_bk;t=[0-9]*//g; s/\r//g' \
      | tail -n "$LINES"
  fi
}

# Print one human line from a status tuple.
print_line() {
  printf '%s\n' "$1" | awk -F'\t' '{
    if ($1 == "poll-error") { print "poll-error (transient)"; next }
    if ($1 == "no-build")   { printf "waiting (newest build#%s %s)\n", $2, $3; next }
    printf "%s %s build=%s %s=%s exit=%s\n", $1, $2, $3, ENVIRON["JOB"], $4, $5
  }' JOB="$JOB"
}

# A job/build state is terminal when the build can no longer change.
is_terminal() {
  printf '%s' "$1" | awk -F'\t' '
    $4 ~ /^(passed|failed|broken)$/ { exit 0 }
    $3 ~ /^(passed|failed|canceled|canceling|blocked)$/ { exit 0 }
    { exit 1 }'
}
# Exit code from the final state: 0 passed, 2 failed-ish.
final_code() {
  printf '%s' "$1" | awk -F'\t' '
    $4 == "passed" { exit 0 }
    $3 == "passed" { exit 0 }
    { exit 2 }'
}

if [ "$JSON" -eq 1 ]; then
  bk build list --pipeline "$PIPELINE" 2>/dev/null | BUILD="$BUILD" COMMIT="$COMMIT" python3 -c '
import sys, json, os
data = json.load(sys.stdin); rows = data if isinstance(data, list) else [data]
bn, c = os.environ.get("BUILD",""), os.environ.get("COMMIT","")
t = (next((b for b in rows if str(b.get("number"))==bn), None) if bn
     else next((b for b in rows if str(b.get("commit","")).startswith(c)), None) if c
     else (rows[0] if rows else None))
print(json.dumps(t, indent=2) if t else "null")
'
  exit 0
fi

if [ "$LOGS" -eq 1 ]; then
  print_logs
  exit $?
fi

if [ "$WATCH" -eq 0 ]; then
  status="$(fetch_status)"
  print_line "$status"
  if is_terminal "$status"; then final_code "$status"; exit $?; fi
  exit 0
fi

# --watch: poll until terminal or timeout, emitting a line on each state change.
deadline=$(( $(date +%s) + TIMEOUT ))
prev=""
while :; do
  status="$(fetch_status)"
  if [ "$status" != "$prev" ]; then
    print_line "$status"
    prev="$status"
  fi
  if is_terminal "$status"; then
    final_code "$status"; exit $?
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "timed out after ${TIMEOUT}s waiting for ${PIPELINE} (last: $(print_line "$status"))"
    exit 3
  fi
  sleep "$INTERVAL"
done
