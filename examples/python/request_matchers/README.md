# Request Matchers Examples

## What it demonstrates

Matching incoming requests by various properties:

- **Path** -- exact, regex, negated
- **Method** -- exact (e.g. GET, POST)
- **Query parameters** -- exact values, regex values
- **Headers** -- exact, regex name and value
- **Cookies** -- combined with query parameters
- **Body** -- exact string, regex, JSON (ignore extra fields), XML
- **Times / TimeToLive** -- limiting how many times or how long an expectation is active

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python request_matchers.py
```

## Expected output

```
expectation created: match by path
expectation created: match by regex path
expectation created: match by path exactly twice
expectation created: match once within 60s
expectation created: match by method
expectation created: match by query parameter
expectation created: match by query parameter regex
expectation created: match by headers
expectation created: match by header regex
expectation created: match by cookies and query parameters
expectation created: match by JSON body
expectation created: match by regex body
expectation created: match by XML body
expectation created: match by exact string body

All request-matcher expectations created successfully.
```
