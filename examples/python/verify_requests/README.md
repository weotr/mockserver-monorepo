# Verify Requests Examples

## What it demonstrates

Verifying that MockServer received specific requests:

- **at_least** -- received at least N times
- **at_most** -- received at most N times
- **exactly** -- received exactly N times
- **between** -- received between N and M times
- **once** -- received exactly once
- **verify_zero_interactions** -- no requests received at all
- **verify_sequence** -- requests arrived in a specific order

The script sets up expectations, sends test requests, and then runs the
verifications. Failed verifications raise `MockServerVerificationError`.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python verify_requests.py
```

## Expected output

```
verification passed: /some/path received at least 2 times
verification passed: /some/path received at most 5 times
verification passed: /some/path received exactly 3 times
verification passed: /some/path received between 1 and 5 times
verification passed: /unique/path received exactly once
verification passed: zero interactions
verification passed: requests arrived in sequence

All verification examples completed.
```
