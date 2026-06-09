# Release Component: JetBrains Plugin

## `scripts/release/components/jetbrains.sh` body

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RELEASE_VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

cd "$REPO_ROOT/mockserver-jetbrains"

# Update version in gradle.properties
sed -i.bak "s/^pluginVersion=.*/pluginVersion=$RELEASE_VERSION/" gradle.properties && rm -f gradle.properties.bak

# Retrieve token from AWS Secrets Manager
JETBRAINS_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/jetbrains \
  --profile mockserver-build \
  --query SecretString --output text)

# Build and publish
export JETBRAINS_TOKEN
./gradlew clean buildPlugin publishPlugin
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# JetBrains Marketplace plugin page responds 200
curl -sf "https://plugins.jetbrains.com/plugin/com.mock-server.mockserver" -o /dev/null \
  && echo "jetbrains: OK" || echo "jetbrains: FAILED"
```

Note: The Marketplace indexing may take a few minutes after upload. A retry with backoff is
recommended in the verify script.
