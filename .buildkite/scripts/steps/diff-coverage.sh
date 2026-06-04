#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# Diff-Coverage Gate
# ────────────────────────────────────────────────────────────────────────────
# Computes line coverage of CHANGED Java lines (git diff vs merge-base with
# master) from JaCoCo XML reports uploaded as Buildkite artifacts.
#
# Threshold: 70% of changed lines must be covered.  When no Java lines
# changed (docs-only, config-only, etc.) the gate passes unconditionally.
#
# The script is intentionally lenient:
#   - Lines in files not present in any jacoco.xml are IGNORED (new modules
#     without coverage, test-only changes, non-instrumented code).
#   - Deleted lines are ignored (only additions count).
#   - Wired as soft_fail in pipeline-java.yml so the build is not
#     hard-blocked during the initial rollout period.
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

THRESHOLD="${DIFF_COVERAGE_THRESHOLD:-70}"
DEFAULT_BRANCH="${BUILDKITE_PULL_REQUEST_BASE_BRANCH:-master}"

# ── Temp directory for artifacts and intermediate files ────────────────────
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# ── Determine the merge-base ───────────────────────────────────────────────
if [ -n "${MERGE_BASE:-}" ]; then
  BASE="$MERGE_BASE"
elif git rev-parse --verify "origin/${DEFAULT_BRANCH}" >/dev/null 2>&1; then
  BASE=$(git merge-base HEAD "origin/${DEFAULT_BRANCH}" 2>/dev/null || echo "HEAD~1")
else
  BASE="HEAD~1"
fi
echo "--- :git: Diff-coverage base: ${BASE:0:10}"

# ── Collect changed Java lines from the diff ───────────────────────────────
# Output: "relative/path/to/File.java:42" per added line
git diff "$BASE"..HEAD -U0 --diff-filter=AM -- '*.java' | \
  python3 -c '
import sys, re

path = None
for line in sys.stdin:
    m = re.match(r"^\+\+\+ b/(.*)", line)
    if m:
        path = m.group(1)
        continue
    m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
    if m and path:
        start = int(m.group(1))
        count = int(m.group(2)) if m.group(2) is not None else 1
        for i in range(start, start + count):
            print(f"{path}:{i}")
' > "$TMPDIR/changed_lines.txt" 2>/dev/null || true

if [ ! -s "$TMPDIR/changed_lines.txt" ]; then
  echo "No changed Java lines — diff-coverage gate passes trivially."
  exit 0
fi

TOTAL_CHANGED=$(wc -l < "$TMPDIR/changed_lines.txt" | tr -d ' ')
echo "Changed Java lines: $TOTAL_CHANGED"

# ── Download JaCoCo XML artifacts ──────────────────────────────────────────
if [ -n "${BUILDKITE:-}" ]; then
  buildkite-agent artifact download "**/target/site/jacoco/jacoco.xml" "$TMPDIR" 2>/dev/null || true
else
  # Local mode: copy jacoco.xml files preserving directory structure
  while IFS= read -r f; do
    dest="$TMPDIR/$f"
    mkdir -p "$(dirname "$dest")"
    cp "$f" "$dest"
  done < <(find . -path "*/target/site/jacoco/jacoco.xml" 2>/dev/null || true)
fi

JACOCO_COUNT=$(find "$TMPDIR" -name "jacoco.xml" 2>/dev/null | wc -l | tr -d ' ')
if [ "$JACOCO_COUNT" -eq 0 ]; then
  echo "No JaCoCo reports found — diff-coverage gate passes (no coverage data)."
  exit 0
fi

# ── Compute diff coverage ─────────────────────────────────────────────────
RESULT=$(python3 - "$TMPDIR/changed_lines.txt" "$TMPDIR" "$THRESHOLD" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET

changed_lines_file = sys.argv[1]
tmpdir = sys.argv[2]
threshold = int(sys.argv[3])

# Read all changed lines: "path/to/File.java:42"
changed = set()
with open(changed_lines_file) as f:
    for raw_line in f:
        raw_line = raw_line.strip()
        if raw_line:
            changed.add(raw_line)

if not changed:
    print("PASS|0|0|0|100.0")
    sys.exit(0)

# Build coverage map: { "package/File.java:line" -> covered }
# JaCoCo XML structure:
#   <report>
#     <package name="org/mockserver/foo">
#       <sourcefile name="Bar.java">
#         <line nr="42" mi="0" ci="3" .../>
coverage_map = {}

for jf in glob.glob(f"{tmpdir}/**/jacoco.xml", recursive=True):
    try:
        tree = ET.parse(jf)
    except ET.ParseError:
        continue
    root = tree.getroot()
    for pkg in root.findall(".//package"):
        pkg_name = pkg.get("name", "")  # e.g. "org/mockserver/matchers"
        for sf in pkg.findall("sourcefile"):
            sf_name = sf.get("name", "")  # e.g. "HttpRequestMatcher.java"
            key_prefix = f"{pkg_name}/{sf_name}"
            for line_el in sf.findall("line"):
                nr = line_el.get("nr")
                ci = int(line_el.get("ci", "0"))
                coverage_map[f"{key_prefix}:{nr}"] = ci > 0

# Match changed lines against coverage data
covered_count = 0
measurable_count = 0
uncovered_lines = []

for cl in sorted(changed):
    # cl = "mockserver/mockserver-core/src/main/java/org/mockserver/matchers/Foo.java:42"
    # Extract the part after src/main/java/ or src/test/java/
    parts = cl.split(":")
    if len(parts) != 2:
        continue
    filepath, line_nr = parts
    for src_root in ("src/main/java/", "src/test/java/"):
        idx = filepath.find(src_root)
        if idx >= 0:
            relative = filepath[idx + len(src_root):]
            key = f"{relative}:{line_nr}"
            if key in coverage_map:
                measurable_count += 1
                if coverage_map[key]:
                    covered_count += 1
                else:
                    uncovered_lines.append(cl)
            break

if measurable_count == 0:
    pct = 100.0
else:
    pct = (covered_count / measurable_count) * 100.0

status = "PASS" if pct >= threshold else "FAIL"
print(f"{status}|{covered_count}|{measurable_count}|{len(changed)}|{pct:.1f}")

# Print uncovered lines for debugging (max 30)
if uncovered_lines:
    print("--- Uncovered changed lines:", file=sys.stderr)
    for ul in uncovered_lines[:30]:
        print(f"  {ul}", file=sys.stderr)
    if len(uncovered_lines) > 30:
        print(f"  ... and {len(uncovered_lines) - 30} more", file=sys.stderr)
PYEOF
)

# Parse result: STATUS|covered|measurable|total_changed|percentage
IFS='|' read -r STATUS COVERED MEASURABLE TOTAL PCT <<< "$RESULT"

echo "--- :bar_chart: Diff-coverage results"
echo "Changed Java lines:  $TOTAL"
echo "Measurable lines:    $MEASURABLE (present in JaCoCo reports)"
echo "Covered lines:       $COVERED"
echo "Diff-coverage:       ${PCT}%"
echo "Threshold:           ${THRESHOLD}%"
echo "Status:              $STATUS"

# Post Buildkite annotation if available
if [ -n "${BUILDKITE:-}" ]; then
  ANNOTATION="### Diff-Coverage: ${PCT}% (threshold: ${THRESHOLD}%)

| Metric | Value |
|---|---:|
| Changed Java lines | $TOTAL |
| Measurable (in JaCoCo) | $MEASURABLE |
| Covered | $COVERED |
| **Coverage** | **${PCT}%** |"

  if [ "$STATUS" = "FAIL" ]; then
    echo "$ANNOTATION" | buildkite-agent annotate --style warning --context "diff-coverage"
  else
    echo "$ANNOTATION" | buildkite-agent annotate --style success --context "diff-coverage"
  fi
fi

if [ "$STATUS" = "FAIL" ]; then
  echo ""
  echo "FAILED: Diff-coverage ${PCT}% is below the ${THRESHOLD}% threshold."
  echo "Add tests for the changed lines or raise the threshold if justified."
  exit 1
fi

echo ""
echo "PASSED: Diff-coverage ${PCT}% meets the ${THRESHOLD}% threshold."
