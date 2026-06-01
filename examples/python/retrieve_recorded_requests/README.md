# Retrieve Recorded Requests Examples

## What it demonstrates

Retrieving data that MockServer has recorded:

- **All recorded requests** -- every request the server received
- **Filtered recorded requests** -- by path and/or method
- **Request/response pairs** -- see the request alongside the response that was returned
- **Active expectations** -- currently registered expectations
- **Log messages** -- server log entries for debugging

The script sets up expectations, sends test traffic, then retrieves and
prints the recorded data.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python retrieve_recorded_requests.py
```

## Expected output

```
all recorded requests (3 total):
  GET /some/path
  POST /some/path
  GET /other/path

recorded POST /some/path (1 total):
  POST /some/path

recorded request/response pairs for /some/path (2 total):
  GET /some/path -> 200
  POST /some/path -> 200

active expectations (2 total):
  path=/some/path  id=...
  path=/other/path  id=...

log messages for /some/path (... entries)
  first: ...

All retrieval examples completed.
```
