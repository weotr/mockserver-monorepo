# OpenAPI Expectations Examples

## What it demonstrates

Generating expectations automatically from an OpenAPI (Swagger) specification:

- Loading a spec from a remote URL
- Mapping specific operations to response status codes
- Passing an inline YAML spec directly

MockServer parses the spec and creates one expectation per operation, with
request matchers and response bodies derived from the schema.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python openapi_expectations.py
```

## Expected output

```
expectation created: OpenAPI from URL (all operations)
expectation created: OpenAPI with specific operations
expectation created: OpenAPI from inline YAML

All OpenAPI expectations created successfully.
```
