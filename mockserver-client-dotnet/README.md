# MockServer.Client for .NET

A hand-written, idiomatic .NET client for [MockServer](https://www.mock-server.com)'s control-plane REST API.

## Installation

```bash
dotnet add package MockServer.Client
```

Or via PackageReference in your `.csproj`:

```xml
<PackageReference Include="MockServer.Client" Version="7.0.1" />
```

## Supported Targets

- .NET Standard 2.0 (for broad compatibility: .NET Framework 4.6.1+, .NET Core 2.0+, Mono 5.4+)
- .NET 8.0 (for modern APIs and best performance)

## Usage

```csharp
using MockServer.Client;
using MockServer.Client.Models;

// Create client
using var client = new MockServerClient("localhost", 1080);

// Create an expectation with fluent API
client.When(
    HttpRequest.Request()
        .WithMethod("GET")
        .WithPath("/hello")
        .WithQueryStringParameter("name", "world")
).Respond(
    HttpResponse.Response()
        .WithStatusCode(200)
        .WithHeader("Content-Type", "application/json")
        .WithBody("{\"message\":\"hello world\"}")
);

// Verify the request was received
client.Verify(
    HttpRequest.Request().WithPath("/hello"),
    VerificationTimes.AtLeastTimes(1)
);

// Reset all expectations
client.Reset();
```

### Forward Expectations

```csharp
client.When(
    HttpRequest.Request().WithPath("/proxy")
).Forward(
    HttpForward.Forward()
        .WithHost("backend.example.com")
        .WithPort(443)
        .WithScheme("HTTPS")
);
```

### Verify Sequence

```csharp
client.VerifySequence(
    HttpRequest.Request().WithPath("/first"),
    HttpRequest.Request().WithPath("/second")
);
```

### Retrieve Recorded Requests

```csharp
var requests = client.RetrieveRecordedRequests(
    HttpRequest.Request().WithPath("/api")
);
```

### Async API

All operations have async variants:

```csharp
await client.VerifyAsync(
    HttpRequest.Request().WithPath("/hello"),
    VerificationTimes.AtLeastTimes(1)
);
```

## Building

```bash
cd mockserver-client-dotnet
dotnet build
dotnet test
```

## Requirements

- .NET SDK 8.0+ (for building)
- No external runtime dependencies beyond `System.Text.Json` (included via .NET or NuGet for netstandard2.0)

## License

Apache 2.0 - see [LICENSE](../LICENSE.md)
