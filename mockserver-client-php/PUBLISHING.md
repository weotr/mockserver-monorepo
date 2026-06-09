# Publishing mockserver-client-php to Packagist

## Overview

The `mock-server/mockserver-client` package is published to [Packagist](https://packagist.org)
via a GitHub webhook. Once the package is registered, Packagist auto-updates whenever a new
git tag is pushed. No CI secret or publish command is needed.

## One-time Setup (already done for this package)

1. Log in to https://packagist.org (GitHub OAuth).
2. Click "Submit" and enter the repository URL:
   `https://github.com/mock-server/mockserver-monorepo`
3. Packagist detects `mockserver-client-php/composer.json` (subdirectory package).
   If Packagist does not auto-detect subdirectory packages, use the monorepo plugin
   approach or register a mirror/subtree-split repo.
4. On the Packagist package page, go to Settings and copy the Packagist API token.
5. In the GitHub repo Settings > Webhooks, add the Packagist webhook:
   - URL: `https://packagist.org/api/github?username=<packagist-username>`
   - Content type: `application/json`
   - Secret: the Packagist API token
   - Events: push events only

**Note on monorepo subdirectory packages:** Packagist natively supports monorepos
if the `composer.json` is at the repository root OR if a tool like
[`symplify/monorepo-builder`](https://github.com/symplify/monorepo-builder) splits
each package to its own read-only git repository. The recommended approach is a
subtree-split to `github.com/mock-server/mockserver-client-php` triggered by the
release pipeline, so that Packagist can index the package at the repo root.

## Publishing a New Version

Publishing happens automatically when a git tag is pushed:

```bash
# From the release pipeline or manually:
git tag 7.0.1   # matches the MockServer release version
git push origin 7.0.1
```

Packagist picks up the tag via the webhook and updates the package index within minutes.
Users install with:

```bash
composer require mock-server/mockserver-client:^7.0
```

## Verification

After pushing a tag, verify the package is live:

```bash
curl -s "https://packagist.org/packages/mock-server/mockserver-client.json" | jq '.package.versions'
```

Or visit: https://packagist.org/packages/mock-server/mockserver-client

## Secret Requirements

**None.** The Packagist webhook uses a token stored in GitHub repo settings (not in
AWS Secrets Manager or CI). The webhook was configured once and requires no rotation
for publishing. No pipeline secret is needed.
