# Release Component: mockserver-client-php

## Pipeline snippet for `scripts/release/components/php-client.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

# PHP client release is triggered by git tag only.
# Packagist auto-updates via GitHub webhook when the tag is pushed.
# No explicit publish command is needed.

RELEASE_VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

echo "--- :php: PHP Client v${RELEASE_VERSION}"
echo "PHP client publishing is handled by Packagist webhook on git tag push."
echo "Tag '${RELEASE_VERSION}' triggers automatic Packagist index update."
echo "No action required in this script — verify on Packagist after tag push."
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# PHP client — verify package is indexed on Packagist
curl -sf "https://packagist.org/packages/mock-server/mockserver-client.json" \
  | jq -e ".package.versions[\"${RELEASE_VERSION}\"]" > /dev/null \
  && echo "php-client: v${RELEASE_VERSION} live on Packagist" \
  || echo "php-client: v${RELEASE_VERSION} NOT YET indexed (webhook may be pending)"
```

## Notes

- **No pipeline secret required.** Publishing is via git tag + Packagist webhook.
- The webhook is configured once in GitHub repo settings (one-time setup).
- If using a subtree-split approach, the release pipeline should run the split
  before pushing the tag to the split repo.
- Packagist typically indexes within 1-2 minutes of tag push.
