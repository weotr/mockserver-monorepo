#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i php:8.2-cli \
  -w /build/mockserver-client-php \
  -- bash -ec '
    # php:8.2-cli ships neither a curl binary nor git/unzip. Composer needs git
    # or unzip to install --prefer-dist, and the installer is fetched via php
    # itself (copy() over openssl) rather than curl.
    apt-get update -qq && apt-get install -y -qq git unzip >/dev/null
    php -r "copy(\"https://getcomposer.org/installer\", \"/tmp/composer-setup.php\");"
    php /tmp/composer-setup.php --install-dir=/usr/local/bin --filename=composer --quiet
    composer install --no-interaction --prefer-dist
    vendor/bin/phpunit --testsuite Unit --colors=never
  '
