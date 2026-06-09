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

## Pre-publish Checklist

1. Ensure `version` in `package.json` matches the release version
2. Run `npm install && npm run compile && npm test`
3. Run `npx vsce package` to verify the .vsix builds cleanly
4. Both publish commands are idempotent for the same version (will fail if already published)

## Version Management

The `version` field in `package.json` tracks the MockServer release version. Update it as part of
the release process (e.g., `npm version 7.0.2 --no-git-tag-version`).
