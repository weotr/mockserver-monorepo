# asdf / mise -- MockServer CLI

asdf (and its Rust successor mise) is a version manager for multiple tools.
MockServer provides an asdf plugin so users can install and switch between
CLI versions with:

```bash
# asdf
asdf plugin add mockserver https://github.com/mock-server/asdf-mockserver
asdf install mockserver latest
asdf global mockserver latest

# mise (drop-in compatible)
mise plugin add mockserver https://github.com/mock-server/asdf-mockserver
mise install mockserver@latest
mise use mockserver@latest
```

## How it works

An asdf plugin is a repository containing executable scripts in `bin/`:

| Script | Purpose |
|--------|---------|
| `bin/list-all` | Lists all available versions (queries GitHub Releases API) |
| `bin/download` | Downloads a specific version binary |
| `bin/install` | Installs the downloaded binary into the versioned path |
| `bin/latest-stable` | Returns the latest stable version |

The plugin detects the user's OS and architecture automatically and downloads
the correct native binary from GitHub Releases.

### Plugin repository

The plugin is hosted at `mock-server/asdf-mockserver` on GitHub. The `bin/`
scripts in this directory are the content of that repository. During release,
the scripts are synced to the plugin repo.

## Publishing a new version

No release-time action is needed for asdf/mise. The plugin scripts query
GitHub Releases dynamically, so publishing a new GitHub Release with the
CLI binaries automatically makes it available to all asdf/mise users.

The release component script `scripts/release/components/asdf.sh` simply
verifies that the plugin scripts are in sync with the plugin repository
and that the new version is discoverable.

### One-time setup

1. Create the `mock-server/asdf-mockserver` repository on GitHub.
2. Push the `bin/` scripts from this directory.
3. Register the plugin in the asdf plugin index:
   https://github.com/asdf-vm/asdf-plugins (submit a PR adding `mockserver`).

## Supported platforms

| OS | Architecture | Binary name |
|----|-------------|-------------|
| Linux | x86_64 | `mockserver-linux-x64` |
| Linux | aarch64 | `mockserver-linux-arm64` |
| macOS | x86_64 | `mockserver-darwin-x64` |
| macOS | arm64 | `mockserver-darwin-arm64` |
| Windows | x86_64 | `mockserver-windows-x64.exe` |

## Finalisation blockers

This plugin finalises once:
1. The CLI's GitHub Releases artifact naming is fixed.
2. The `mock-server/asdf-mockserver` repository is created.
3. The plugin is registered in the asdf plugin index.

All `TODO(cli-release):` markers in the `bin/` scripts must be resolved.
