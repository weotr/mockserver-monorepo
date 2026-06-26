#!/usr/bin/env bash
#
# Collects FAILING-test artefacts and prints an end-of-log pass/fail summary.
#
# After the :maven: build, this:
#   1. Scans every **/target/{surefire,failsafe}-reports/TEST-*.xml.
#   2. For each <testsuite> with failures>0 or errors>0, copies its TEST-*.xml
#      AND the matching <className>-output.txt (produced by
#      redirectTestOutputToFile=true) into mockserver/target/failed-tests/<module>/.
#   3. Echoes an at-a-glance END-OF-LOG summary block: total passed / failed /
#      skipped across all modules, plus the failing class names and where their
#      logs were collected.
#
# This is the bottom-of-log roll-up the developer reads first; the sticky
# java-summarize.sh annotation stays as the top-of-build annotation.
#
# This script NEVER fails the build — it always exits 0. The build's own exit
# code is preserved by the caller (java-build-and-collect.sh).

set -uo pipefail

# Resolve the repo root so we scan/copy in the same place whether invoked from
# the repo root (CI) or elsewhere. This script lives in
# .buildkite/scripts/steps/, so the repo root is three levels up.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Allow an override (used by the self-test) to point at an arbitrary scan root.
SCAN_ROOT="${COLLECT_FAILURES_SCAN_ROOT:-$REPO_ROOT}"
# The failures destination is fixed by the artifact_paths glob in
# pipeline-java.yml (mockserver/target/failed-tests/**/*). Allow override for
# the self-test.
DEST_DIR="${COLLECT_FAILURES_DEST:-$REPO_ROOT/mockserver/target/failed-tests}"

python3 - "$SCAN_ROOT" "$DEST_DIR" <<'PYEOF'
import glob
import os
import shutil
import sys
import xml.etree.ElementTree as ET

scan_root = sys.argv[1]
dest_root = sys.argv[2]


def module_from(path):
    # path looks like: .../mockserver/mockserver-core/target/surefire-reports/TEST-Foo.xml
    parts = path.split(os.sep)
    try:
        idx = parts.index("target")
        return parts[idx - 1]
    except ValueError:
        return "unknown"


total_passed = 0
total_failed = 0
total_skipped = 0
failing = []  # list of (module, classname, xml_path)

patterns = [
    os.path.join(scan_root, "**", "target", "surefire-reports", "TEST-*.xml"),
    os.path.join(scan_root, "**", "target", "failsafe-reports", "TEST-*.xml"),
]
xml_files = []
for p in patterns:
    xml_files.extend(glob.glob(p, recursive=True))

for f in sorted(set(xml_files)):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    if root.tag != "testsuite":
        continue
    tests = int(root.get("tests", 0))
    failures = int(root.get("failures", 0))
    errors = int(root.get("errors", 0))
    skipped = int(root.get("skipped", 0))
    passed = tests - failures - errors - skipped
    total_passed += passed
    total_skipped += skipped
    total_failed += failures + errors

    if failures > 0 or errors > 0:
        module = module_from(f)
        classname = root.get("name") or os.path.basename(f)[len("TEST-"):-len(".xml")]
        failing.append((module, classname, f))

        dest_dir = os.path.join(dest_root, module)
        os.makedirs(dest_dir, exist_ok=True)
        # Copy the failing class's XML.
        shutil.copy2(f, os.path.join(dest_dir, os.path.basename(f)))
        # Copy its matching <className>-output.txt if present (produced by
        # redirectTestOutputToFile=true; lives alongside the XML).
        out_txt = os.path.join(os.path.dirname(f), classname + "-output.txt")
        if os.path.isfile(out_txt):
            shutil.copy2(out_txt, os.path.join(dest_dir, os.path.basename(out_txt)))

# ---- emit end-of-log summary block ----
if dest_root.startswith(scan_root):
    dest_display = os.path.relpath(dest_root, scan_root)
else:
    dest_display = dest_root

print("")
print("+++ :bar_chart: Test summary (end of log)")
print("    passed : %d" % total_passed)
print("    failed : %d" % total_failed)
print("    skipped: %d" % total_skipped)
if failing:
    print("")
    print("    Failing test classes (logs collected under %s/<module>/):" % dest_display)
    for module, classname, _ in failing:
        print("      - [%s] %s" % (module, classname))
    print("")
    print("    Download the 'failed-tests' artefact on the :maven: build step for full console output.")
else:
    print("")
    print("    No failing test classes. :tada:")
PYEOF

# Always succeed — never fail the build from artefact collection.
exit 0
