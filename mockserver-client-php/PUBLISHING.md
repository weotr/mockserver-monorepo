# Publishing mockserver-client-php to Packagist

## Overview

The `mock-server/mockserver-client` package is published to [Packagist](https://packagist.org)
through a **dedicated read-only mirror repository**, because Packagist requires
`composer.json` at the **repository root** of the default branch and does **not**
support subdirectory packages. Submitting the monorepo URL fails with
*"No composer.json was found in the master branch"* — the package's `composer.json`
lives at `mockserver-client-php/composer.json`, not at the monorepo root.

The mirror repo is:

```
github.com/mock-server/mockserver-client-php   (master = subtree split of mockserver-client-php/)
```

The **monorepo stays the single source of truth** — the PHP client is developed,
built, tested, and versioned here. The mirror is regenerated from the monorepo at
release time and is never edited directly.

## One-time Setup (already done for this package)

1. Create the public mirror repo `mock-server/mockserver-client-php`.
2. Push a subtree split of `mockserver-client-php/` to its `master`, so
   `composer.json` sits at the repo root:
   ```bash
   git subtree split --prefix=mockserver-client-php -b php-split
   git push git@github.com:mock-server/mockserver-client-php.git php-split:refs/heads/master
   ```
3. On https://packagist.org (GitHub OAuth), click **Submit** and enter the
   **mirror** URL: `https://github.com/mock-server/mockserver-client-php`.
   Packagist finds `composer.json` at the root and registers
   `mock-server/mockserver-client`.
4. Enable auto-updates on the **mirror** repo — either authorize Packagist's
   GitHub integration when prompted at submit, or add the Packagist webhook
   manually in the mirror repo's **Settings → Webhooks**:
   - URL: `https://packagist.org/api/github?username=<packagist-username>`
   - Content type: `application/json`
   - Secret: the Packagist API token (from the package's Settings page)
   - Events: push events only

## Publishing a New Version

Publishing is automated by the release pipeline
(`scripts/release/components/client-php.sh`). On each release it:

1. Regenerates the mirror with `git subtree split --prefix=mockserver-client-php`.
2. Pushes the split to the mirror's `master`.
3. Pushes the version tag (e.g. `7.0.1`) to the **mirror** repo.

The Packagist webhook on the mirror picks up the tag and updates the package
index within minutes. Users install with:

```bash
composer require mock-server/mockserver-client:^7.0
```

> The release uses the standard github-token (`mockserver-release/github-token`,
> which has org `repo` write) to push to the mirror — no separate publish secret
> is required.

## Verification

After a release, verify the package is live:

```bash
curl -s "https://packagist.org/packages/mock-server/mockserver-client.json" | jq '.package.versions | keys'
```

Or visit: https://packagist.org/packages/mock-server/mockserver-client

## Secret Requirements

**No dedicated publish secret.** Pushing to the mirror reuses
`mockserver-release/github-token`. The Packagist webhook token lives in the
**mirror** repo's GitHub settings (not in AWS Secrets Manager or CI) and needs no
rotation for publishing.
