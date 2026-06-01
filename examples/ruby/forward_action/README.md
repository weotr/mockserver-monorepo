# Forward Action

## What it demonstrates

How to forward incoming requests to upstream services, including:

- Simple forward to an HTTP host
- Forward to an HTTPS host
- Overriding the forwarded request (path, headers)
- Overriding both the forwarded request and the response
- Forwarding with a custom socket address (host, port, scheme)

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby forward_action.rb
```

## Expected output

```
1. Created expectation: /api/users -> forward to backend.example.com:80 (HTTP)
2. Created expectation: /api/secure -> forward to secure.example.com:443 (HTTPS)
3. Created expectation: /some/path -> forward with overridden path and Host header
4. Created expectation: /override/both -> forward with overridden request and response
5. Created expectation: /custom/host -> forward to target.host.com:1234 (HTTPS)

All expectations cleared.
```
