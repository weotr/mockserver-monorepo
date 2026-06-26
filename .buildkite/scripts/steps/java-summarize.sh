#!/usr/bin/env bash
#
# Posts a Buildkite annotation summarising coverage for the just-finished Java build:
#   - per-module unit-test LINE coverage from jacoco.xml
#   - link to the downloadable jacoco-html-reports.tar.gz artifact
#
# Runs as a separate pipeline step after the :maven: build step uploads its
# artifacts; downloads the jacoco XMLs back into a temp dir and renders markdown.
#
# NOTE: the per-class TEST-*.xml are NO LONGER uploaded by the :maven: build step
# (only failing tests are collected under mockserver/target/failed-tests/**), so
# this script no longer attempts to download them or render a test pass/fail
# table. The full pass/fail counts are printed in the build log by
# java-build-and-collect.sh, and failing-test artefacts appear on the build step.

set -euo pipefail

TMPDIR=$(mktemp -d)
trap 'rm -rf "${TMPDIR}"' EXIT

cd "${TMPDIR}"

# Pull the jacoco XML files we need. `|| true` so the step still posts an
# annotation if the maven step partially failed and only some XMLs exist.
buildkite-agent artifact download "**/target/site/jacoco/jacoco.xml" . 2>/dev/null || true
buildkite-agent artifact download "**/target/site/jacoco-it/jacoco.xml" . 2>/dev/null || true

# Render markdown summary.
MARKDOWN=$(python3 - "${TMPDIR}" <<'PYEOF'
import glob
import os
import sys
import xml.etree.ElementTree as ET

tmpdir = sys.argv[1]


def module_from(path):
    # path looks like: mockserver/mockserver-core/target/site/jacoco/jacoco.xml
    parts = path.split(os.sep)
    try:
        idx = parts.index("target")
        return parts[idx - 1]
    except ValueError:
        return "unknown"


coverage = {}
for f in glob.glob(f"{tmpdir}/**/target/site/jacoco/jacoco.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    module = module_from(f)
    for c in root.findall("counter"):
        if c.get("type") == "LINE":
            missed = int(c.get("missed", 0))
            covered = int(c.get("covered", 0))
            total = missed + covered
            coverage[module] = (covered / total) if total else 0.0
            break

# ---- emit markdown ----
lines = []

if coverage:
    lines.append("### Coverage (LINE, unit-test)")
    lines.append("")
    lines.append("| Module | LINE % |")
    lines.append("|---|---:|")
    for module in sorted(coverage):
        lines.append(f"| {module} | {coverage[module] * 100:.2f}% |")
    lines.append("")
    lines.append("Download the full HTML coverage report: `jacoco-html-reports.tar.gz` artifact on the :maven: build step.")
    lines.append("")

print("\n".join(lines))
PYEOF
)

if [[ -z "${MARKDOWN}" ]]; then
    echo "No coverage data found - skipping annotation"
    exit 0
fi

echo "${MARKDOWN}" | buildkite-agent annotate --style "info" --context "java-summary"
