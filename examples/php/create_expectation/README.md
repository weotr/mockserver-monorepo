# Create Expectation

## What it demonstrates

How to use the MockServer PHP client to:

- Create a simple expectation (GET /hello -> 200 with a text body)
- Send a real HTTP request through MockServer to exercise the expectation
- Verify that the request was received at least once
- Reset all expectations

## Prerequisites

- PHP 8.1+
- Composer dependencies installed (`cd ../../../mockserver-client-php && composer install`)
- MockServer running on `localhost:1080`

## Run

```bash
php create_expectation.php
```

## Expected output

```
1. Created expectation: GET /hello -> 200 "Hello from PHP!"

--- Test request: GET /hello ---
Status: 200
Body:   Hello from PHP!

2. Verified: GET /hello received at least once

All expectations cleared.
```
