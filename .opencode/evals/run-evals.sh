#!/usr/bin/env bash
# Offline evaluation harness for AI components.
# See .opencode/rules/evaluation-harness.md and .opencode/evals/README.md
#
# Exit codes: 0 = OK (no regressions), 1 = a golden task regressed,
#             2 = a fixture is malformed. STRICT=1 makes PENDING count as failure.
set -uo pipefail

EVAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TASK_DIR="$EVAL_DIR/tasks"
REQUIRED_KEYS="id category agent expected_verdict"
VALID_VERDICTS="PASS BLOCK FLAG"
STRICT="${STRICT:-0}"

# frontmatter <file> <key> -> value (reads the leading --- … --- block only)
frontmatter() {
  awk -v k="$2" '
    /^---[[:space:]]*$/ { n++; next }
    n==1 && $0 ~ "^"k":" { sub("^"k":[[:space:]]*", ""); sub(/[[:space:]]*$/, ""); print; exit }
  ' "$1"
}

[ -d "$TASK_DIR" ] || { echo "no tasks dir: $TASK_DIR"; exit 2; }

total=0; pass=0; fail=0; pending=0; malformed=0

shopt -s nullglob
for f in "$TASK_DIR"/*.md; do
  total=$((total + 1))
  base="$(basename "$f" .md)"
  id="$(frontmatter "$f" id)"

  miss=""
  for k in $REQUIRED_KEYS; do
    [ -z "$(frontmatter "$f" "$k")" ] && miss="$miss $k"
  done
  if [ -n "$miss" ]; then
    echo "MALFORMED $base: missing frontmatter:$miss"; malformed=$((malformed + 1)); continue
  fi
  if [ "$id" != "$base" ]; then
    echo "MALFORMED $base: id '$id' does not match filename stem"; malformed=$((malformed + 1)); continue
  fi

  agent="$(frontmatter "$f" agent)"
  exp="$(frontmatter "$f" expected_verdict)"
  case " $VALID_VERDICTS " in
    *" $exp "*) ;;
    *) echo "MALFORMED $id: expected_verdict '$exp' not in {$VALID_VERDICTS}"; malformed=$((malformed + 1)); continue ;;
  esac
  # review agents emit only PASS/BLOCK, never FLAG — catch the misconfiguration early
  case "$agent" in
    *review*) [ "$exp" = "FLAG" ] && { echo "MALFORMED $id: review agent '$agent' cannot emit FLAG (use BLOCK)"; malformed=$((malformed + 1)); continue; } ;;
  esac

  res="$TASK_DIR/$id.result"
  if [ -f "$res" ]; then
    act="$(tr -d '[:space:]' < "$res")"
    case " $VALID_VERDICTS " in
      *" $act "*) ;;
      *) echo "MALFORMED $id: .result contains '$act', expected one of {$VALID_VERDICTS}"; malformed=$((malformed + 1)); continue ;;
    esac
    if [ "$act" = "$exp" ]; then
      echo "PASS    $id (agent=$agent expected=$exp)"; pass=$((pass + 1))
    else
      echo "FAIL    $id (agent=$agent expected=$exp got=$act)"; fail=$((fail + 1))
    fi
  else
    echo "PENDING $id (agent=$agent expected=$exp) — run the agent on this fixture, then write the verdict to tasks/$id.result"
    pending=$((pending + 1))
  fi
done

# orphan .result files (a fixture was renamed or removed)
for r in "$TASK_DIR"/*.result; do
  [ -e "$r" ] || continue
  stem="$(basename "$r" .result)"
  [ -f "$TASK_DIR/$stem.md" ] || echo "WARNING: orphan result $(basename "$r") has no matching fixture"
done

echo "----"
echo "tasks=$total pass=$pass fail=$fail pending=$pending malformed=$malformed strict=$STRICT"

# An empty suite must NOT vacuously pass the gate.
[ "$total" -gt 0 ] || { echo "NO TASKS FOUND — gate vacuously satisfied; add fixtures before rollout"; exit 2; }
[ "$malformed" -gt 0 ] && { echo "FIXTURES MALFORMED"; exit 2; }
[ "$fail" -gt 0 ] && { echo "REGRESSION: golden task(s) failed"; exit 1; }
if [ "$STRICT" = "1" ] && [ "$pending" -gt 0 ]; then
  echo "STRICT: $pending pending task(s) have no recorded result"; exit 1
fi
echo "OK (no regressions in recorded results)"
exit 0
