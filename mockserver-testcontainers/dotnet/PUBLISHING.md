# Publishing Testcontainers.MockServer to NuGet

## Prerequisites

- .NET 8.0 SDK installed
- NuGet API key with push permissions for `Testcontainers.MockServer`

## Secret

The NuGet API key is stored in AWS Secrets Manager:

```
mockserver-release/nuget
```

Retrieve it with:

```bash
NUGET_API_KEY=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/nuget \
  --query SecretString --output text \
  --profile mockserver-build)
```

## Build and Publish (non-interactive)

```bash
cd mockserver-testcontainers/dotnet

# Restore and build in Release mode
dotnet restore
dotnet build -c Release --no-restore

# Run unit tests
dotnet test -c Release --no-build --filter "Category!=Integration"

# Pack the NuGet package
dotnet pack src/Testcontainers.MockServer/Testcontainers.MockServer.csproj \
  -c Release --no-build \
  -o ./artifacts

# Push to NuGet.org
dotnet nuget push ./artifacts/Testcontainers.MockServer.*.nupkg \
  --source https://api.nuget.org/v3/index.json \
  -k "$NUGET_API_KEY"
```

## Version Management

The package version is defined in `Directory.Build.props` as `<MockServerVersion>`. It is
updated by the release pipeline to match the MockServer release version (SNAPSHOT suffix stripped).

## Liveness Check

After publishing, verify the package is live:

```bash
dotnet nuget list source https://api.nuget.org/v3/index.json \
  --format short | grep Testcontainers.MockServer
```

Or check: `https://www.nuget.org/packages/Testcontainers.MockServer/<VERSION>`

<!-- eventual split: if this module moves to its own repo, the NuGet package name stays the same -->
