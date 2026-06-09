# Release Component: @mockserver/testcontainers

## Component script body (`scripts/release/components/testcontainers-node.sh`)

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPONENT_DIR="mockserver-testcontainers/node"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

cd "${REPO_ROOT}/${COMPONENT_DIR}"

# Update package.json version
node -e "
  const pkg = require('./package.json');
  pkg.version = '${VERSION}';
  require('fs').writeFileSync('package.json', JSON.stringify(pkg, null, 2) + '\n');
"

# Also update the default image tag in source
sed -i.bak "s/mockserver-[0-9]\+\.[0-9]\+\.[0-9]\+/mockserver-${VERSION}/" src/mockserver-container.ts
rm -f src/mockserver-container.ts.bak

# Build and publish
npm ci
npm run build
npm run test:unit

NPM_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-build/npm-token \
  --query SecretString --output text)

echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" > .npmrc
npm publish --access public
rm -f .npmrc
```

## Liveness check (`scripts/release/components/verify.sh` entry)

```bash
# @mockserver/testcontainers — verify published version is available on npm
npm view @mockserver/testcontainers@${RELEASE_VERSION} version
```

This command exits 0 and prints the version string if the package is live on the registry.
