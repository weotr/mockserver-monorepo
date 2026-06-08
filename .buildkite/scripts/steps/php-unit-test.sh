#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i php:8.2-cli \
  -w /build/mockserver-client-php \
  -- bash -c '
    # Install composer
    curl -sS https://getcomposer.org/installer | php -- --install-dir=/usr/local/bin --filename=composer
    composer install --no-interaction --prefer-dist
    vendor/bin/phpunit --testsuite Unit --colors=never
  '
