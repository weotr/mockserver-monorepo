# Forward Override (Static Proxy Modification)

## What it demonstrates

How to use MockServer's `httpOverrideForwardedRequest` from PHP to **statically
modify a proxied exchange** -- rewriting the forwarded request and overriding the
response:

- Create an "upstream" mock expectation that returns a JSON response
- Create an `httpOverrideForwardedRequest` expectation (via the raw REST API)
  that rewrites requests from `/proxy` to `/upstream` on the same MockServer,
  and overrides the response body and adds a custom header
- Send a request to `/proxy` and show the overridden response

This is the **static** approach to modifying proxied traffic. It is defined at
expectation-creation time and does not require a live callback connection.

> **Note:** The PHP client does not include WebSocket support, so interactive
> breakpoints (the `AddRequestResponseBreakpoint` API available in Go, .NET,
> Rust, Node, Python, and Ruby) are not available from PHP. This static
> `httpOverrideForwardedRequest` is the recommended alternative.

## Prerequisites

- PHP 8.1+ with cURL extension
- Composer dependencies installed (`cd ../../../mockserver-client-php && composer install`)
- MockServer running on `localhost:1080`

## Run

```bash
php forward_override.php
```

## Expected output

```
1. Created upstream expectation: GET /upstream -> 200
2. Created forward override: GET /proxy -> forward to /upstream (localhost:1080)
   Response will be overridden with modified body and X-Modified-By header

3. Sending GET /proxy ...

--- Response from GET /proxy ---
Status:          200
Body:            {"source":"upstream","modified":true,"override":"php-client"}
X-Modified-By:   php-forward-override

All expectations cleared.
```
