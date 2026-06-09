# Release Component: testcontainers-mockserver (Rust / crates.io)

## Component script body (`scripts/release/components/testcontainers-rust.sh`)

```bash
#!/usr/bin/env bash
set -euo pipefail

RELEASE_VERSION="${RELEASE_VERSION:?}"
CRATE_DIR="mockserver-testcontainers/rust"

# Update version in Cargo.toml and source constant
sed -i "s/^version = .*/version = \"${RELEASE_VERSION}\"/" "${CRATE_DIR}/Cargo.toml"
sed -i "s/pub const MOCKSERVER_VERSION: &str = .*/pub const MOCKSERVER_VERSION: \&str = \"${RELEASE_VERSION}\";/" "${CRATE_DIR}/src/lib.rs"

# Retrieve token
CARGO_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/crates \
  --query SecretString --output text \
  --region eu-west-2 | jq -r .CARGO_TOKEN)

# Publish
cd "${CRATE_DIR}"
cargo publish --token "${CARGO_TOKEN}"
```

## Liveness check (`scripts/release/components/verify.sh` entry)

```bash
# testcontainers-mockserver (crates.io)
curl -sf "https://crates.io/api/v1/crates/testcontainers-mockserver/${RELEASE_VERSION}" \
  | jq -e '.version.num == "'"${RELEASE_VERSION}"'"'
```
