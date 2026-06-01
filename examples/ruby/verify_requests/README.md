# Verify Requests

## What it demonstrates

How to verify that MockServer received specific requests, including:

- Verify a request was received at least N times
- Verify a request was received exactly N times
- Verify a request was received at most N times
- Verify a request was received between N and M times
- Verify requests arrived in a specific sequence
- Verify zero interactions for a path
- Handling a `VerificationError` when verification fails

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby verify_requests.rb
```

## Expected output

```
Sent 4 requests (2x /some/path, 1x /other/path, 1x /third/path)
1. Verified: /some/path received at least once
2. Verified: /some/path received exactly 2 times
3. Verified: /other/path received at most once
4. Verified: /some/path received between 1 and 3 times
5. Verified sequence: /some/path -> /other/path -> /third/path
6. Verified: /never/called received exactly 0 times
7. Expected verification failure: Request not found exactly 99 times...

All expectations cleared.
```
