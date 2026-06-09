#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The composer:2 image bundles PHP + composer + git + unzip, so the step needs
# no apt-get (and therefore no root) — it runs as the non-root default. This
# also drops the only step that previously required root for an OS package
# install.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i composer:2 \
  -w /build/mockserver-client-php \
  -- bash -ec '
    composer install --no-interaction --prefer-dist
    vendor/bin/phpunit --testsuite Unit --colors=never
  '
