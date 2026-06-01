# Forward Action Examples

## What it demonstrates

Creating expectations that forward matching requests to another server:

- Forward via HTTP or HTTPS
- Forward with a delay
- Override the forwarded request (path, headers)
- Override the response returned from the upstream
- Forward to a specific socket address (host:port separate from the Host header)
- Modify the forwarded request with a `requestModifier` (add/remove headers, rewrite path)

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python forward_action.py
```

## Expected output

```
expectation created: forward via HTTP
expectation created: forward via HTTPS
expectation created: forward with delay
expectation created: forward with overridden request
expectation created: forward with overridden request and response
expectation created: forward with explicit socket address
expectation created: forward with request modifier

All forward-action expectations created successfully.
```
