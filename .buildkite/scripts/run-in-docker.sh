#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

IMAGE=""
DOCKER_ARGS=()
COMMAND_ARGS=()
WORKDIR="/build"
MEMORY=""
NETWORK=""
DOCKER_SOCKET=false
ENTRYPOINT=""
ENV_VARS=()
VOLUMES=()
CACHE_TYPES=()
# Security posture for untrusted PR builds. NOTE: the agents already run Docker
# with user-namespace remapping (DOCKER_USERNS_REMAP=true, elastic-ci-stack
# default), so container-root is already an unprivileged host UID — a breakout
# is not host-root. On top of that:
#  - The Docker socket (-s) is ALWAYS withheld from PR builds (L3) — the highest
#    -value protection and a safe skip (a socket bypasses the userns boundary).
#  - --harden adds defense-in-depth compatible with userns-remap: cap-drop=ALL +
#    no-new-privileges. (We deliberately do NOT add --user: that double-remaps
#    the UID and breaks workspace writes on the agents.) Use --cap-add to restore
#    a capability a step needs.
HARDEN=false
CAP_ADDS=()

usage() {
  cat <<EOF
Usage: run-in-docker.sh [OPTIONS] -- COMMAND [ARGS...]

Runs a command inside a Docker container with the repo mounted at /build.
Logs the full docker run command at the start for easy local reproduction.

Options:
  -i, --image IMAGE        Docker image to use (required)
  -w, --workdir DIR        Working directory inside container (default: /build)
  -m, --memory SIZE        Memory limit (e.g. 7g)
  -s, --docker-socket      Mount Docker socket into container
  --entrypoint CMD         Override container entrypoint
  -e, --env KEY=VALUE      Pass environment variable to container
  -v, --volume SRC:DST     Additional volume mount
  --cache TYPE             Mount dependency cache (maven|npm|pip|bundler|gradle|go|cargo|nuget)
  --network NAME           Docker network to connect to
  --harden                 Add no-new-privileges + cap-drop=ALL (agents already userns-remap)
  --cap-add CAP            With --harden, add a Linux capability back
  -h, --help               Show this help

Examples:
  .buildkite/scripts/run-in-docker.sh -i node:22 -w /build/mockserver-ui -- npm ci && npm test
  .buildkite/scripts/run-in-docker.sh -i python:3.12 -s -- bash -c 'cd mockserver-client-python && pytest'
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--image)   IMAGE="$2"; shift 2 ;;
    -w|--workdir) WORKDIR="$2"; shift 2 ;;
    -m|--memory)  MEMORY="$2"; shift 2 ;;
    -s|--docker-socket) DOCKER_SOCKET=true; shift ;;
    --entrypoint) ENTRYPOINT="$2"; shift 2 ;;
    -e|--env)     ENV_VARS+=("$2"); shift 2 ;;
    -v|--volume)  VOLUMES+=("$2"); shift 2 ;;
    --cache)      CACHE_TYPES+=("$2"); shift 2 ;;
    --network)    NETWORK="$2"; shift 2 ;;
    --harden)     HARDEN=true; shift ;;
    --cap-add)    CAP_ADDS+=("$2"); shift 2 ;;
    -h|--help)    usage ;;
    --)           shift; COMMAND_ARGS=("$@"); break ;;
    *)            COMMAND_ARGS=("$@"); break ;;
  esac
done

if [[ -z "$IMAGE" ]]; then
  echo "Error: --image is required"
  exit 1
fi

if [[ ${#COMMAND_ARGS[@]} -eq 0 ]]; then
  echo "Error: no command specified after --"
  exit 1
fi

DOCKER_ARGS+=(--rm)
DOCKER_ARGS+=(-v "$REPO_ROOT:/build")
DOCKER_ARGS+=(-w "$WORKDIR")

# ---------------------------------------------------------------------------
# Hardening (opt-in via --harden): capability dropping + no privilege escalation
# ---------------------------------------------------------------------------
# IMPORTANT: the build agents already run Docker with user-namespace remapping
# (DOCKER_USERNS_REMAP=true in the elastic-ci-stack), so container-root is
# ALREADY mapped to an unprivileged host UID — a breakout is not host-root. We
# therefore must NOT add `--user` here: a non-root container UID gets a SECOND
# remap and no longer matches the workspace owner, which breaks writes to the
# bind-mounted /build (this is exactly why per-step non-root passed locally but
# failed on CI). --harden adds the defense-in-depth that IS compatible with
# userns-remap: drop all Linux capabilities + block privilege escalation. Add a
# capability back per-step with --cap-add when a tool needs one.
HOME_DIR="/root"
if [[ "$HARDEN" == "true" ]]; then
  DOCKER_ARGS+=(--security-opt no-new-privileges)
  DOCKER_ARGS+=(--cap-drop ALL)
  for cap in "${CAP_ADDS[@]+"${CAP_ADDS[@]}"}"; do
    DOCKER_ARGS+=(--cap-add "$cap")
  done
fi

if [[ -n "$MEMORY" ]]; then
  DOCKER_ARGS+=(--memory="$MEMORY" --memory-swap="$MEMORY")
fi

if [[ -n "$ENTRYPOINT" ]]; then
  DOCKER_ARGS+=(--entrypoint "$ENTRYPOINT")
fi

if [[ -n "$NETWORK" ]]; then
  DOCKER_ARGS+=(--network "$NETWORK")
fi

if [[ "$DOCKER_SOCKET" == "true" ]]; then
  # L3: the Docker socket gives a container full control of the host daemon
  # (trivial host-root breakout). Untrusted PR code must NOT receive it — these
  # socket-mounting steps (Testcontainers / helm integration) are skipped on PR
  # builds and run only on the trusted default branch (post-merge). Override for
  # a specific trusted PR with ALLOW_PR_DOCKER_SOCKET=true.
  PR="${BUILDKITE_PULL_REQUEST:-false}"
  if [[ "$PR" != "false" && "${ALLOW_PR_DOCKER_SOCKET:-false}" != "true" ]]; then
    {
      echo "+++ :lock: Skipping Docker-socket step on PR build #${PR}"
      echo "The Docker socket is withheld from untrusted PR code (host-root breakout risk)."
      echo "This step runs on the default branch after merge. To force on a trusted PR,"
      echo "set ALLOW_PR_DOCKER_SOCKET=true."
    } >&2
    exit 0
  fi
  DOCKER_ARGS+=(-v /var/run/docker.sock:/var/run/docker.sock)
fi

for env_var in "${ENV_VARS[@]+"${ENV_VARS[@]}"}"; do
  DOCKER_ARGS+=(-e "$env_var")
done

for vol in "${VOLUMES[@]+"${VOLUMES[@]}"}"; do
  DOCKER_ARGS+=(-v "$vol")
done

# ---------------------------------------------------------------------------
# Dependency cache volume mounts (fail-safe: skip silently if dir missing)
# ---------------------------------------------------------------------------
# Each --cache TYPE maps a workspace-local .buildkite-cache/<type> directory
# into the container at the tool's default cache location. If the directory
# does not exist (cache-restore.sh was skipped, failed, or cache missed),
# we create an empty one so the mount point exists -- the build proceeds
# with an empty cache (equivalent to a cold build).
# ---------------------------------------------------------------------------
CACHE_BASE="${BUILDKITE_BUILD_CHECKOUT_PATH:-${REPO_ROOT}}/.buildkite-cache"
for cache_type in "${CACHE_TYPES[@]+"${CACHE_TYPES[@]}"}"; do
  host_dir="${CACHE_BASE}/${cache_type}"
  # Ensure the host directory exists (empty is fine -- cold build)
  mkdir -p "$host_dir" 2>/dev/null || true
  # Cache targets follow $HOME so they work whether the container runs as root
  # ($HOME=/root) or non-root ($HOME=/tmp). Bundler installs gems under a
  # GEM_HOME that defaults to a root-only path in the ruby image, so under
  # non-root we redirect it to a writable $HOME/bundle.
  case "$cache_type" in
    maven)   DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.m2/repository") ;;
    npm)     DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.npm") ;;
    pip)     DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.cache/pip") ;;
    gradle)  DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.gradle/caches") ;;
    go)      DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/go/pkg/mod") ;;
    cargo)   DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.cargo/registry") ;;
    nuget)   DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.nuget/packages") ;;
    bundler) DOCKER_ARGS+=(-v "${host_dir}:/usr/local/bundle/cache") ;;
    *)       echo "[run-in-docker] WARNING: unknown cache type '${cache_type}' -- ignored" >&2 ;;
  esac
done

quote_arg() {
  if [[ "$1" =~ [[:space:]\&\|\;\$\(\)\{\}\<\>\`\\] ]]; then
    local escaped="${1//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    escaped="${escaped//\$/\\\$}"
    escaped="${escaped//\`/\\\`}"
    printf '"%s"' "$escaped"
  else
    printf '%s' "$1"
  fi
}

# Build a redacted display version so the logged command never echoes secrets.
# For `-e KEY=VAL` we keep KEY but replace VAL with `***`. The COMMAND_ARGS
# (typically the bash heredoc body) is not redacted, so callers MUST avoid
# embedding secrets literally in the command body and must instead use env
# vars passed via -e.
DISPLAY_ARGS=()
redact_next=false
for arg in "${DOCKER_ARGS[@]}"; do
  if $redact_next; then
    redact_next=false
    if [[ "$arg" == *=* ]]; then
      DISPLAY_ARGS+=("$(quote_arg "${arg%%=*}=***")")
    else
      DISPLAY_ARGS+=("***")
    fi
    continue
  fi
  if [[ "$arg" == -e || "$arg" == --env ]]; then
    redact_next=true
    DISPLAY_ARGS+=("$(quote_arg "$arg")")
    continue
  fi
  DISPLAY_ARGS+=("$(quote_arg "$arg")")
done
DISPLAY_CMD_ARGS=()
for arg in "${COMMAND_ARGS[@]}"; do
  DISPLAY_CMD_ARGS+=("$(quote_arg "$arg")")
done

FULL_CMD="docker run ${DISPLAY_ARGS[*]} $IMAGE ${DISPLAY_CMD_ARGS[*]}"

# Log to stderr so callers can still capture the wrapped command's stdout.
{
  echo "┌──────────────────────────────────────────────────────────────────"
  echo "│ Docker Command (copy to reproduce locally):"
  echo "│"
  echo "│   $FULL_CMD"
  echo "│"
  echo "│ Or from repo root:"
  echo "│   cd $(pwd) && $FULL_CMD"
  echo "│"
  echo "└──────────────────────────────────────────────────────────────────"
  echo ""
} >&2

exec docker run "${DOCKER_ARGS[@]}" "$IMAGE" "${COMMAND_ARGS[@]}"
