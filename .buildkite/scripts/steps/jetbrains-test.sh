#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Plain JDK 17 image: the Gradle wrapper provides Gradle (9.5.1, required by the
# IntelliJ Platform plugin 2.16), so the base image only needs a JDK.
#
# This step runs as root (the default), so the build runs inside an in-container
# copy under /tmp (NOT the mounted workspace): Gradle + the IntelliJ Platform
# plugin write build/, .gradle/, .kotlin/ and .intellijPlatform/ as root, and
# left in the workspace those root-owned files break the next build's git
# checkout/clean (a known buildkite elastic-stack issue). Building in /tmp keeps
# the mounted workspace pristine. (Once this step is validated under --harden it
# can run non-root directly in the workspace and drop the /tmp copy.)
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i eclipse-temurin:17-jdk \
  -w /build \
  -- bash -ec '
    cp -a mockserver-jetbrains /tmp/jb
    cd /tmp/jb
    ./gradlew test --no-daemon
  '
