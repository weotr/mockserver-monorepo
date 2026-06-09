# Release Component: mockserver-vscode

## `scripts/release/components/vscode.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../../../mockserver-vscode"

# Update version in package.json
npm version "${RELEASE_VERSION}" --no-git-tag-version

# Install, compile, and test
npm ci
npm run compile
npm test

# Publish to VS Code Marketplace
export VSCE_PAT=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/vsce \
  --query SecretString --output text)
npx vsce publish -p "$VSCE_PAT"

# Publish to Open VSX
export OVSX_PAT=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/ovsx \
  --query SecretString --output text)
npx ovsx publish -p "$OVSX_PAT"
```

## Liveness Check for `scripts/release/components/verify.sh`

```bash
# Verify VS Code Marketplace publication
curl -sf "https://marketplace.visualstudio.com/items?itemName=mock-server.mockserver" | grep -q "${RELEASE_VERSION}"
```
