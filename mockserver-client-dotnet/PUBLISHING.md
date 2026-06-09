# Publishing MockServer.Client to NuGet

## Package

- **Package ID:** `MockServer.Client`
- **Registry:** [nuget.org](https://www.nuget.org/packages/MockServer.Client)
- **Secret:** `mockserver-release/nuget` (AWS Secrets Manager, `NUGET_API_KEY` field)

## Non-interactive publish command

```bash
# 1. Build and pack
cd mockserver-client-dotnet
dotnet pack src/MockServer.Client/MockServer.Client.csproj -c Release -o ./artifacts

# 2. Push to NuGet (requires NUGET_API_KEY environment variable)
dotnet nuget push ./artifacts/MockServer.Client.*.nupkg \
  --api-key "$NUGET_API_KEY" \
  --source https://api.nuget.org/v3/index.json \
  --skip-duplicate
```

## Retrieving the secret

```bash
NUGET_API_KEY=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/nuget \
  --query SecretString --output text \
  --profile mockserver-build)
```

## Version management

The package version is set in `src/MockServer.Client/MockServer.Client.csproj` in the `<Version>` property.
It must match the MockServer release version (read from `mockserver/pom.xml`, strip `-SNAPSHOT`).

## Liveness check

After publishing, verify the package is live:

```bash
dotnet nuget list source --format short | grep -q nuget.org && \
  curl -sf "https://api.nuget.org/v3-flatcontainer/mockserver.client/index.json" | \
  python3 -c "import sys,json; versions=json.load(sys.stdin)['versions']; print(versions[-1])"
```

## Eventual module split

When this package is eventually split to its own repository (`github.com/mock-server/mockserver-client-dotnet`),
update the `RepositoryUrl` in the `.csproj` and the NuGet source link configuration accordingly.
