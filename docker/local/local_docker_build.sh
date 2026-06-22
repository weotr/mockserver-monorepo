#!/usr/bin/env bash

# Resolve the repository root and the current MockServer version so this script
# is portable across machines (no hardcoded home directory or version).
REPO_ROOT="$(git -C "$(cd "$(dirname "$0")" && pwd)" rev-parse --show-toplevel)"
VERSION="$(cd "${REPO_ROOT}/mockserver" && ./mvnw -q -o help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)"
M2_REPO="${HOME}/.m2/repository"

cp "${M2_REPO}/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar" ./mockserver-netty-jar-with-dependencies.jar
docker build --no-cache -t mockserver/mockserver:local-snapshot .
