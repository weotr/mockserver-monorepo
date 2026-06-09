# Publishing mockserver-client to crates.io

## Prerequisites

- Rust toolchain (stable): `rustup update stable`
- `cargo` CLI (included with Rust)
- API token for crates.io with publish permission

## Secret

The crates.io API token is stored in AWS Secrets Manager:

```
Secret: mockserver-release/crates
Key:    CARGO_TOKEN
```

Retrieve it via:
```sh
CARGO_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/crates \
  --query SecretString --output text \
  --profile mockserver-build | jq -r '.CARGO_TOKEN')
```

## Publish Command (non-interactive)

```sh
cd mockserver-client-rust
cargo publish --token "$CARGO_TOKEN"
```

## Version Management

The crate version in `Cargo.toml` tracks the MockServer release version (strip
`-SNAPSHOT` from `mockserver/pom.xml`). The release pipeline bumps it before
publishing.

## Verification

After publishing, verify the crate is live:

```sh
# Check crates.io API (may take a few minutes to index)
curl -s https://crates.io/api/v1/crates/mockserver-client | jq '.crate.max_version'
```

## Eventual split

If this crate is ever moved to its own repository, the package name
(`mockserver-client`) and crate ownership on crates.io remain unchanged.
