# Response Action

## What it demonstrates

How to create expectations that return canned HTTP responses, including:

- Simple body-only response
- Response with status code, headers, and cookies
- Response with a delay
- Sequenced responses for the same path (using `Times.exactly`)
- Custom status code and reason phrase (418 I'm a teapot)

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby response_action.rb
```

## Expected output

```
1. Created expectation: GET /hello -> 200 "Hello, World!"
2. Created expectation: POST /login -> 201 with headers and cookies
3. Created expectation: GET /slow -> 200 after 2s delay
4. Created expectations: GET /counter -> "first" then "second"
5. Created expectation: POST /teapot -> 418 I'm a teapot

--- Sending test request to GET /hello ---
Status: 200
Body:   Hello, World!

All expectations cleared.
```
