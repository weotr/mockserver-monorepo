# Release Component: mockserver-client-php

## How it publishes

Packagist requires `composer.json` at the **repository root** and does not support
subdirectory packages, so the PHP client is published through a read-only mirror
repo whose root is the package:

```
github.com/mock-server/mockserver-client-php   (master = subtree split of mockserver-client-php/)
```

The release component `scripts/release/components/client-php.sh` regenerates and
pushes that mirror on each release. See
[`PUBLISHING.md`](PUBLISHING.md) for the full rationale and one-time setup.

## What `scripts/release/components/client-php.sh` does

```bash
# 1. Validate mockserver-client-php/composer.json (exists + valid JSON).
# 2. Subtree-split mockserver-client-php/ into a root-level commit:
#      git subtree split --prefix=mockserver-client-php
# 3. Push the split to the mirror's master:
#      git push https://github.com/mock-server/mockserver-client-php.git <split>:refs/heads/master
# 4. Push the version tag to the mirror (idempotent):
#      git push https://github.com/mock-server/mockserver-client-php.git <split>:refs/tags/${RELEASE_VERSION}
# Auth uses mockserver-release/github-token (org repo write). Best-effort/soft —
# any failure is non-fatal and never blocks the release.
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# PHP client — verify package is indexed on Packagist
curl -sf "https://packagist.org/packages/mock-server/mockserver-client.json" \
  | jq -e ".package.versions[\"${RELEASE_VERSION}\"]" > /dev/null \
  && echo "PHP client: ${RELEASE_VERSION} live on Packagist" \
  || echo "PHP client: ${RELEASE_VERSION} NOT YET indexed (webhook may be pending)"
```

## Notes

- **No dedicated pipeline secret.** Pushing the mirror reuses
  `mockserver-release/github-token`; the Packagist webhook token lives in the
  **mirror** repo's GitHub settings.
- The Packagist webhook is configured once on the **mirror** repo (not the
  monorepo), so the pushed tag triggers indexing within 1-2 minutes.
- The monorepo remains the single source of truth; the mirror is regenerated
  from it at release time and is never edited directly.
