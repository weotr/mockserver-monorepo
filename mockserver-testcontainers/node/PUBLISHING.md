# Publishing @mockserver/testcontainers

## Registry

npm — scoped package `@mockserver/testcontainers` (public).

## Prerequisites

- Node.js >= 18
- npm CLI authenticated with a token that has publish access to the `@mockserver` scope

## Secret

The npm publish token is stored in AWS Secrets Manager:
- Secret name: `mockserver-build/npm-token`
- Used in the Buildkite release pipeline

## Publish Command (non-interactive)

```bash
cd mockserver-testcontainers/node
npm ci
npm run build
npm publish --access public
```

To publish with an explicit token (CI):

```bash
NPM_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-build/npm-token \
  --query SecretString --output text)

echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" > .npmrc
npm publish --access public
rm -f .npmrc
```

## Version Bump

Update `version` in `package.json` to match the MockServer release version before publishing.
The release pipeline sets this automatically.
