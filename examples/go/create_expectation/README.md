# Create Expectation

## What it demonstrates

How to use the MockServer Go client to:

- Create a simple expectation (GET /hello -> 200 with a text body)
- Send a real HTTP request through MockServer to exercise the expectation
- Verify that the request was received at least once
- Reset all expectations

## Prerequisites

- Go 1.21+
- MockServer running on `localhost:1080`

## Run

```bash
go run .
```

## Expected output

```
1. Created expectation: GET /hello -> 200 "Hello from Go!"

--- Test request: GET /hello ---
Status: 200
Body:   Hello from Go!

2. Verified: GET /hello received at least once

All expectations cleared.
```
