# Release Component: mockserver-client-dotnet

## `scripts/release/components/dotnet-client.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPONENT_DIR="mockserver-client-dotnet"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

cd "$COMPONENT_DIR"

# Update version in .csproj
sed -i "s|<Version>.*</Version>|<Version>${VERSION}</Version>|" \
  src/MockServer.Client/MockServer.Client.csproj

# Build and pack
dotnet pack src/MockServer.Client/MockServer.Client.csproj -c Release -o ./artifacts

# Push to NuGet
dotnet nuget push ./artifacts/MockServer.Client.${VERSION}.nupkg \
  --api-key "$NUGET_API_KEY" \
  --source https://api.nuget.org/v3/index.json \
  --skip-duplicate
```

## Secret

- **Name:** `mockserver-release/nuget`
- **Key field:** `NUGET_API_KEY`
- **Required scope:** Push packages to nuget.org

## Liveness check for `scripts/release/components/verify.sh`

```bash
# dotnet-client: verify published to NuGet
curl -sf "https://api.nuget.org/v3-flatcontainer/mockserver.client/${RELEASE_VERSION}/mockserver.client.${RELEASE_VERSION}.nupkg" \
  -o /dev/null \
  && echo "dotnet-client: OK (NuGet ${RELEASE_VERSION})" \
  || echo "dotnet-client: FAILED (not found on NuGet)"
```
