# Publishing the MockServer JetBrains Plugin

## Registry

JetBrains Marketplace: https://plugins.jetbrains.com/

## Plugin ID

`com.mock-server.mockserver`

## Non-interactive publish command

```bash
cd mockserver-jetbrains
JETBRAINS_TOKEN="$JETBRAINS_TOKEN" ./gradlew publishPlugin
```

## Secret

| Secret path | Key | Purpose |
|---|---|---|
| `mockserver-release/jetbrains` | `JETBRAINS_TOKEN` | JetBrains Marketplace upload token |

The token is stored in AWS Secrets Manager under the `mockserver-build` account. Retrieve with:

```bash
aws secretsmanager get-secret-value \
  --secret-id mockserver-release/jetbrains \
  --profile mockserver-build \
  --query SecretString --output text
```

## Plugin signing (optional, recommended)

For signed plugin distribution, set these additional environment variables:

- `CERTIFICATE_CHAIN` — the full certificate chain (PEM)
- `PRIVATE_KEY` — the private key (PEM)
- `PRIVATE_KEY_PASSWORD` — passphrase for the private key

## Versioning

The plugin version is derived from the MockServer project version (currently `7.0.1`). It is set in `gradle.properties` as `pluginVersion` and should be updated in lockstep with the MockServer release version.

## Eventual split note

If the plugin is ever extracted to a separate repository, the natural home would be `github.com/mock-server/mockserver-jetbrains`.
