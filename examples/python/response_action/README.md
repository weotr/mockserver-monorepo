# Response Action Examples

## What it demonstrates

Creating expectations that return canned HTTP responses, including:

- Plain-text and JSON bodies
- Custom status codes and reason phrases
- Response headers and cookies
- Delayed responses
- Different responses for the same request path (using `Times`)
- Connection options (suppressing headers)

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python response_action.py
```

## Expected output

```
expectation created: response with body only
expectation created: 418 I'm a teapot
expectation created: response with header
expectation created: response with cookie
expectation created: JSON body response
expectation created: response with 2s delay
expectation created: different responses for same path
expectation created: response with connection options

All response-action expectations created successfully.
```
