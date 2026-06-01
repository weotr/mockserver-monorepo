# Reverse Proxy Example

## What it demonstrates

Using MockServer as a reverse proxy that routes traffic to different backends
based on the request path:

- `/rest/*` requests are forwarded to a local backend (`127.0.0.1:8080` via HTTP)
- All other requests are forwarded to a QA environment (`192.168.50.10:443` via HTTPS)

Both rules use unlimited `Times`, so they apply to every matching request
indefinitely. The more specific `/rest.*` rule is registered first and takes
priority.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python reverse_proxy.py
```

## Expected output

```
proxy rule created: /rest/* -> 127.0.0.1:8080 (HTTP)
proxy rule created: /* -> 192.168.50.10:443 (HTTPS)

Reverse proxy configured. MockServer is now forwarding traffic.
Press Ctrl-C to exit (expectations remain until MockServer is reset).
```

Press `Ctrl-C` to stop the script. The forwarding rules remain active on the
MockServer until it is reset or the expectations are cleared.
