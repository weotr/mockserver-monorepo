# Publishing MockServer VS Code Extension

## Registries

The extension is published to two registries:

1. **VS Code Marketplace** (primary) - for VS Code Desktop and vscode.dev
2. **Open VSX** - for Eclipse Theia, Gitpod, and other compatible editors

## Secrets

| Secret Path | Purpose |
|-------------|---------|
| `mockserver-release/vsce` | Personal Access Token for VS Code Marketplace |
| `mockserver-release/ovsx` | Personal Access Token for Open VSX Registry |

Secrets are stored in AWS Secrets Manager under the `mockserver-release` prefix.

## Non-interactive Publish Commands

### VS Code Marketplace

```bash
# Retrieve the PAT
export VSCE_PAT=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/vsce \
  --query SecretString --output text \
  --profile mockserver-build)

# Publish
npx vsce publish -p "$VSCE_PAT"
```

### Open VSX Registry

```bash
# Retrieve the PAT
export OVSX_PAT=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/ovsx \
  --query SecretString --output text \
  --profile mockserver-build)

# Publish
npx ovsx publish -p "$OVSX_PAT"
```

## Marketplace listing page & screenshots

Unlike JetBrains (which needs a manual gallery upload), the VS Code Marketplace and Open VSX **render this
extension's [`README.md`](README.md) as the listing page** — so whatever is in the README, including
screenshots, IS the marketplace page, published automatically on release. There is no separate gallery to
upload to.

**Screenshots must be referenced by ABSOLUTE `https://` URL.** Relative image paths break on the Marketplace:
`vsce` rewrites them against the `repository` field (`github.com/mock-server/mockserver`), which is the wrong
repo for this monorepo. The README therefore points at the public website copies:

- `https://www.mock-server.com/images/vscode_action_panel.png` — the MockServer side panel (Activity Bar)
- `https://www.mock-server.com/images/vscode_status_menu.png` — the status-bar quick menu

The image **files** live in [`../jekyll-www.mock-server.com/images/`](../jekyll-www.mock-server.com/images/)
(committed as `vscode_*.png`) and are also shown on the website's IDE Extensions page. The website and the
extension publish together on release, so the absolute URLs resolve by the time the Marketplace page is live.

**On each release**, to keep the listing current:
1. If the UI changed, recapture the screenshot(s), drop the new PNG into `jekyll-www.mock-server.com/images/`
   (keep the same `vscode_*.png` filename so the README URLs still resolve), and update the website IDE
   Extensions page if needed.
2. Edit `README.md` (the listing copy + any new `https://www.mock-server.com/images/...` screenshot) — no manual
   marketplace upload is needed; `vsce publish` / `ovsx publish` pushes the README on release.
3. The marketplace ICON is separate from screenshots: it is `media/icon.png` (wired via `package.json` `"icon"`).

## Pre-publish Checklist

1. Ensure `version` in `package.json` matches the release version
2. Run `npm install && npm run compile && npm test`
3. Run `npx vsce package` to verify the .vsix builds cleanly
4. Both publish commands are idempotent for the same version (will fail if already published)

## Version Management

The `version` field in `package.json` tracks the MockServer release version. Update it as part of
the release process (e.g., `npm version 7.0.2 --no-git-tag-version`).
