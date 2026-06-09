# winget -- MockServer CLI

winget is the Windows Package Manager. MockServer publishes native CLI binaries
as a winget package under the identifier `MockServer.MockServer`.

## How it works

winget manifests live in the community repository
[microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs). Each
version is a YAML manifest file at:

```
manifests/m/MockServer/MockServer/<VERSION>/MockServer.MockServer.yaml
```

Publishing a new version means opening a PR to that repo with the updated
manifest. The `wingetcreate` CLI automates this.

## Publishing a new version

The release component script `scripts/release/components/winget.sh` automates
the process:

1. Download the release binaries from GitHub Releases.
2. Compute SHA256 hashes.
3. Run `wingetcreate update` to generate an updated manifest.
4. Submit a PR to `microsoft/winget-pkgs` via `wingetcreate submit`.

### Prerequisites

- `wingetcreate` CLI (or run in the Docker image `wingetcreate`)
- A GitHub personal access token with `public_repo` scope (stored in
  `mockserver-release/winget-github-token` in AWS Secrets Manager)

### Manual fallback

```bash
wingetcreate update MockServer.MockServer \
  --version <VERSION> \
  --urls https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<VERSION>/mockserver-windows-x64.exe \
         https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<VERSION>/mockserver-windows-arm64.exe \
  --submit --token <GITHUB_PAT>
```

## Manifest format

The `MockServer.MockServer.yaml` file in this directory is the template/reference
manifest. It uses the winget singleton format (v1.9.0) and includes both x64
and arm64 installer entries.

## Finalisation blockers

This manifest finalises once the CLI's GitHub Releases artifact naming is fixed.
All `TODO(cli-release):` markers in the YAML must be resolved before the first
submission.
