# Create Expectation

## What it demonstrates

How to use the MockServer .NET client to:

- Create a simple expectation (GET /hello -> 200 with a text body)
- Send a real HTTP request through MockServer to exercise the expectation
- Verify that the request was received at least once
- Reset all expectations

## Prerequisites

- .NET SDK 8.0+
- MockServer running on `localhost:1080`

## Run

```bash
dotnet run
```

## Expected output

```
1. Created expectation: GET /hello -> 200 "Hello from .NET!"

--- Test request: GET /hello ---
Status: 200
Body:   Hello from .NET!

2. Verified: GET /hello received at least once

All expectations cleared.
```
