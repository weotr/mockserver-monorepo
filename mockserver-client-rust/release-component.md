# Release Component: mockserver-client-rust

## `scripts/release/components/rust-client.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

RELEASE_VERSION="${RELEASE_VERSION:?}"
CARGO_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/crates \
  --query SecretString --output text | jq -r '.CARGO_TOKEN')

cd mockserver-client-rust

# Update version in Cargo.toml
sed -i "s/^version = \".*\"/version = \"${RELEASE_VERSION}\"/" Cargo.toml

# Build, test, publish
cargo test
cargo publish --token "$CARGO_TOKEN"
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# Rust client — verify crate is published on crates.io
curl -sf "https://crates.io/api/v1/crates/mockserver-client/${RELEASE_VERSION}" \
  | jq -e '.version.num' >/dev/null
```
