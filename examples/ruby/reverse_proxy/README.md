# Reverse Proxy

## What it demonstrates

How to use MockServer as a reverse proxy that selectively routes traffic:

- REST API requests (`/rest.*`) are forwarded to a local backend (127.0.0.1:8080)
- All other requests (`/.*`) are forwarded to a remote environment (192.168.50.10:443)

This pattern is useful during development when you want to work on a specific
backend service locally while routing the rest of the traffic to a shared
staging or QA environment.

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby reverse_proxy.rb
```

## Expected output

```
1. Forwarding /rest.* -> 127.0.0.1:8080 (HTTP)
2. Forwarding /.* -> 192.168.50.10:443 (HTTPS)

Reverse proxy configured.
REST API requests go to the local backend; everything else goes to the remote environment.
Press Ctrl-C to stop.
```
