#!/usr/bin/env bash
# Build MockServer binary bundles for every supported platform from a single host.
#
# For each target {os}/{arch} this downloads a same-version Temurin JDK (for its
# platform-native jmods), then runs build-binary-bundle.sh using the HOST jlink
# against those jmods (cross-build). The host jlink major version must match
# --jdk-version (jlink can target another platform of the same major version).
#
# Targets (default): linux/x86_64 linux/aarch64 darwin/x86_64 darwin/aarch64 windows/x86_64
#
# Usage:
#   scripts/build-all-bundles.sh --jar <path> --version <ver> \
#       [--jdk-version 21] [--cache <dir>] [--output <dir>] \
#       [--targets "linux/x86_64 windows/x86_64"]
#
# Downloaded JDKs are cached under --cache (default .tmp/jdks) and reused.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$SCRIPT_DIR/build-binary-bundle.sh"

JAR=""
VERSION=""
JDK_VERSION="21"
CACHE=".tmp/jdks"
OUTPUT="target/bundles"
TARGETS=(linux/x86_64 linux/aarch64 darwin/x86_64 darwin/aarch64 windows/x86_64)

log() { printf '\033[1;36m[all-bundles]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[all-bundles] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)         JAR="$2"; shift 2 ;;
    --version)     VERSION="$2"; shift 2 ;;
    --jdk-version) JDK_VERSION="$2"; shift 2 ;;
    --cache)       CACHE="$2"; shift 2 ;;
    --output)      OUTPUT="$2"; shift 2 ;;
    --targets)     read -r -a TARGETS <<< "$2"; shift 2 ;;
    -h|--help)     grep '^# ' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

[[ -n "$JAR" && -f "$JAR" ]] || die "--jar <existing shaded jar> is required"
[[ -n "$VERSION" ]]          || die "--version is required"
[[ -x "$BUILD" ]]            || die "builder not found/executable: $BUILD"
command -v curl >/dev/null   || die "curl is required"
command -v tar >/dev/null    || die "tar is required"
command -v unzip >/dev/null  || die "unzip is required (for windows JDK archives)"

# Host jlink major must match the target JDK major (cross-build constraint).
# Prefer JAVA_HOME (a real JDK home); else resolve `jlink` symlinks (apt-installed
# JDKs put an alternatives symlink at /usr/bin/jlink, which would break dirname).
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jlink" ]]; then
  HOST_JLINK="$JAVA_HOME/bin/jlink"
else
  HOST_JLINK="$(command -v jlink 2>/dev/null || true)"
  HOST_JLINK="$(readlink -f "$HOST_JLINK" 2>/dev/null || echo "$HOST_JLINK")"
fi
[[ -n "$HOST_JLINK" && -x "$HOST_JLINK" ]] || die "host jlink not found (set JAVA_HOME)"
HOST_MAJOR="$("$HOST_JLINK" --version 2>&1 | grep -oE '^[0-9]+' | head -1)"
[[ "$HOST_MAJOR" == "$JDK_VERSION" ]] || die "host jlink is JDK $HOST_MAJOR but --jdk-version=$JDK_VERSION; they must match"

# Map our {os}/{arch} -> Adoptium {os}/{arch}/{archive ext}.
adoptium_triplet() {
  case "$1" in
    linux/x86_64)   echo "linux x64 tar.gz" ;;
    linux/aarch64)  echo "linux aarch64 tar.gz" ;;
    darwin/x86_64)  echo "mac x64 tar.gz" ;;
    darwin/aarch64) echo "mac aarch64 tar.gz" ;;
    windows/x86_64) echo "windows x64 zip" ;;
    *) return 1 ;;
  esac
}

mkdir -p "$CACHE" "$OUTPUT"
BUILT=()

for target in "${TARGETS[@]}"; do
  OS="${target%%/*}"; ARCH="${target##*/}"
  triplet="$(adoptium_triplet "$target")" || die "unsupported target: $target (use os/arch)"
  read -r AOS AARCH AEXT <<< "$triplet"

  TDIR="$CACHE/temurin-${JDK_VERSION}-${AOS}-${AARCH}"
  JMODS="$(find "$TDIR" -maxdepth 4 -type d -name jmods 2>/dev/null | head -1 || true)"
  if [[ -z "$JMODS" ]]; then
    log "Downloading Temurin $JDK_VERSION $AOS/$AARCH ..."
    URL="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/${AOS}/${AARCH}/jdk/hotspot/normal/eclipse?project=jdk"
    ARCHIVE="$CACHE/temurin-${JDK_VERSION}-${AOS}-${AARCH}.${AEXT}"
    curl -fsSL -o "$ARCHIVE" "$URL" || die "download failed for $target ($URL)"
    rm -rf "$TDIR"; mkdir -p "$TDIR"
    if [[ "$AEXT" == "zip" ]]; then unzip -qo "$ARCHIVE" -d "$TDIR"; else tar -xzf "$ARCHIVE" -C "$TDIR"; fi
    JMODS="$(find "$TDIR" -maxdepth 4 -type d -name jmods 2>/dev/null | head -1 || true)"
    [[ -n "$JMODS" ]] || die "no jmods found in extracted JDK for $target (under $TDIR)"
    rm -f "$ARCHIVE"   # reclaim ~200 MB/target once jmods are extracted
  else
    log "Reusing cached JDK for $target"
  fi

  log "Building bundle for $OS/$ARCH ..."
  "$BUILD" --jar "$JAR" --version "$VERSION" --os "$OS" --arch "$ARCH" \
           --jlink-home "$(dirname "$(dirname "$HOST_JLINK")")" --jmods "$JMODS" \
           --output "$OUTPUT" >/dev/null
  BUILT+=("$OS/$ARCH")
done

log "Built ${#BUILT[@]} bundle(s): ${BUILT[*]}"
log "Artifacts in: $OUTPUT"
ls -1 "$OUTPUT"/mockserver-"$VERSION"-*.{tar.gz,zip} 2>/dev/null || true
