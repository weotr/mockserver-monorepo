# Chocolatey -- MockServer CLI

Chocolatey is a Windows package manager. MockServer publishes the CLI as a
Chocolatey package so users can install with:

```powershell
choco install mockserver
```

## How it works

Chocolatey packages are `.nupkg` files (NuGet format) containing:
- `mockserver.nuspec` -- package metadata
- `tools/chocolateyinstall.ps1` -- install script (downloads the native binary)
- `tools/chocolateyuninstall.ps1` -- uninstall script (removes binary + shim)

Packages are pushed to the Chocolatey Community Repository at
https://community.chocolatey.org using `choco push`.

## Publishing a new version

The release component script `scripts/release/components/chocolatey.sh`:

1. Downloads release binaries from GitHub Releases and computes SHA256.
2. Substitutes version and checksum placeholders in the `.nuspec` and install script.
3. Runs `choco pack` to build the `.nupkg`.
4. Runs `choco push` to upload to chocolatey.org.

### Prerequisites

- `choco` CLI (or run in a Windows Docker image with Chocolatey installed)
- A Chocolatey API key (stored in `mockserver-release/chocolatey-api-key` in
  AWS Secrets Manager)

### Manual fallback

```powershell
# Update version + checksums in .nuspec and chocolateyinstall.ps1
choco pack .\mockserver.nuspec
choco push mockserver.<VERSION>.nupkg --source https://push.chocolatey.org/ --api-key <KEY>
```

## Moderation

Chocolatey Community Repository submissions go through automated and manual
moderation. First submissions take longer (1-2 weeks). Subsequent versions
with no structural changes are typically auto-approved.

## Finalisation blockers

This package finalises once the CLI's GitHub Releases artifact naming is fixed.
All `TODO(cli-release):` markers in the `.nuspec` and `.ps1` files must be
resolved before the first submission.
