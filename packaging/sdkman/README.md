# SDKMAN! -- MockServer CLI

SDKMAN! is a tool for managing parallel versions of SDKs on Unix-like systems
(and Windows via WSL/Git Bash). MockServer registers as a candidate so users
can install the CLI with:

```bash
sdk install mockserver
```

## How it works

SDKMAN! uses a Vendor API -- there are no manifest files to commit to an
external repository. Instead, the MockServer release pipeline calls the API
to register new versions. The API is documented at https://sdkman.io/vendors.

### One-time setup

Before the first release, the `mockserver` candidate must be registered with
the SDKMAN! team:

1. Open an issue at https://github.com/sdkman/sdkman-cli/issues requesting
   a new candidate.
2. Provide: candidate name (`mockserver`), description, website URL, whether
   it's platform-specific (yes -- native binaries).
3. Once approved, you receive vendor API credentials (consumer key + consumer
   token) which are stored in AWS Secrets Manager at
   `mockserver-release/sdkman-vendor`.

### Platform binaries

SDKMAN! supports platform-specific distributions. The release script registers
download URLs for each platform:

| SDKMAN! Platform | Binary |
|------------------|--------|
| `LINUX_64` | `mockserver-linux-x64` |
| `LINUX_ARM64` | `mockserver-linux-arm64` |
| `MAC_OSX` | `mockserver-darwin-x64` |
| `MAC_ARM64` | `mockserver-darwin-arm64` |
| `WINDOWS_64` | `mockserver-windows-x64.exe` |

SDKMAN! expects the download URL to point to a `.zip` archive containing the
binary. If the CLI release publishes bare binaries, the release script wraps
each in a zip first.

## Publishing a new version

The release component script `scripts/release/components/sdkman.sh`:

1. Calls `POST /release` on the Vendor API to register the new version with
   download URLs for each platform.
2. Calls `PUT /default` to set the new version as the default.
3. Calls `POST /announce` to broadcast the release.

### Manual fallback

```bash
# Register a new version
curl -X POST https://vendors.sdkman.io/release \
  -H "Consumer-Key: $SDKMAN_KEY" \
  -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "candidate": "mockserver",
    "version": "<VERSION>",
    "platform": "LINUX_64",
    "url": "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<VERSION>/mockserver-linux-x64.zip"
  }'

# Set as default
curl -X PUT https://vendors.sdkman.io/default \
  -H "Consumer-Key: $SDKMAN_KEY" \
  -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"candidate": "mockserver", "version": "<VERSION>"}'

# Announce
curl -X POST https://vendors.sdkman.io/announce/struct \
  -H "Consumer-Key: $SDKMAN_KEY" \
  -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"candidate": "mockserver", "version": "<VERSION>", "hashtag": "mockserver"}'
```

## Finalisation blockers

This candidate configuration finalises once:
1. The CLI's GitHub Releases artifact naming is fixed.
2. The `mockserver` candidate is registered with SDKMAN! (one-time).
3. Vendor API credentials are stored in AWS Secrets Manager.

All `TODO(cli-release):` markers in `candidate.yaml` must be resolved.
