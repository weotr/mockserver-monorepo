#!/usr/bin/env bash
# Shared helper: provision a host JDK 21 that provides `jlink`, on demand.
#
# Used by BOTH the release `binary` component
# (scripts/release/components/binary.sh) and the snapshot bundle CI step
# (.buildkite/scripts/steps/java-publish-snapshot-bundles.sh) so the
# bootstrap logic lives in exactly one place and cannot drift between them.
#
# IMPORTANT: the JDK 21 returned here is used ONLY to run `jlink` for building
# the JVM-less binary bundles. It never changes the Maven build JDK (which stays
# on 17) — callers point JAVA_HOME at the returned home for the bundle build
# invocation only (a subshell env), so adding 21 here cannot pull the 17 build
# onto a newer JDK.
#
# Usage:
#   source ".../scripts/lib/ensure-host-jdk21.sh"
#   JDK21_HOME="$(ensure_host_jdk21 "<cache_dir>")" || exit 1
#   JAVA_HOME="$JDK21_HOME" scripts/build-all-bundles.sh --jdk-version 21 ...
#
# Echoes the JDK 21 home (the dir whose bin/jlink is a major-21 jlink) on stdout.
# All progress/diagnostic output goes to stderr so stdout carries only the path.

# jlink major version of the JDK whose `jlink` binary is at $1.
_jlink_major() { "$1" --version 2>&1 | grep -oE '^[0-9]+' | head -1 || true; }

ensure_host_jdk21() {
  local cache_dir="${1:?ensure_host_jdk21: cache dir required}"

  # 1. Reuse an existing JDK 21 already on the agent (JAVA_HOME or PATH `jlink`).
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jlink" && "$(_jlink_major "$JAVA_HOME/bin/jlink")" == "21" ]]; then
    echo "$JAVA_HOME"; return 0
  fi
  if command -v jlink >/dev/null 2>&1 && [[ "$(_jlink_major "$(command -v jlink)")" == "21" ]]; then
    local path_jlink resolved
    path_jlink="$(command -v jlink)"
    # `readlink -f` resolves alternatives symlinks (Linux); on a non-symlink
    # binary (common on macOS) it can exit non-zero / empty — fall back to the
    # PATH location so we never echo a bogus "." home.
    resolved="$(readlink -f "$path_jlink" 2>/dev/null)" || true
    [[ -n "$resolved" ]] || resolved="$path_jlink"
    dirname "$(dirname "$resolved")"; return 0
  fi

  # 2. Otherwise bootstrap Temurin 21 on demand into the cache (no agent AMI
  #    change required). Reused across runs once present.
  local aos aarch
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)  aos=linux; aarch=x64 ;;
    Linux/aarch64) aos=linux; aarch=aarch64 ;;
    Darwin/x86_64) aos=mac;   aarch=x64 ;;
    Darwin/arm64)  aos=mac;   aarch=aarch64 ;;
    *) echo "Error: unsupported host platform $(uname -s)/$(uname -m) for JDK 21 bootstrap" >&2; return 1 ;;
  esac

  local host_jdk_dir="$cache_dir/host-jdk-21"
  if [[ ! -x "$host_jdk_dir/bin/jlink" && ! -x "$host_jdk_dir/Contents/Home/bin/jlink" ]]; then
    echo "No host JDK 21 found — bootstrapping Temurin 21 ($aos/$aarch) for jlink only..." >&2
    mkdir -p "$host_jdk_dir"
    local archive="$cache_dir/host-jdk-21.tar.gz"
    # `latest/21/ga` is intentional — any GA JDK 21 build is sufficient for jlink.
    # Retry to ride out transient Adoptium API hiccups (mirrors the JAR download).
    curl -fsSL --retry 3 --retry-delay 5 --connect-timeout 30 -o "$archive" \
      "https://api.adoptium.net/v3/binary/latest/21/ga/${aos}/${aarch}/jdk/hotspot/normal/eclipse?project=jdk" \
      || { echo "Error: failed to download Temurin 21 for the host" >&2; return 1; }
    tar -xzf "$archive" -C "$host_jdk_dir" --strip-components=1
    rm -f "$archive"
  fi

  local jdk21_home
  if [[ -x "$host_jdk_dir/bin/jlink" ]]; then
    jdk21_home="$host_jdk_dir"
  else
    jdk21_home="$host_jdk_dir/Contents/Home"   # macOS layout
  fi

  [[ -x "$jdk21_home/bin/jlink" ]] || { echo "Error: could not provision a JDK 21 jlink" >&2; return 1; }
  echo "$jdk21_home"
}
