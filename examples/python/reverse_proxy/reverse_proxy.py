#!/usr/bin/env python3
"""MockServer Python client -- reverse proxy example.

Configures MockServer as a reverse proxy that routes requests to different
backends based on the path:

  /rest/*   -> local backend (127.0.0.1:8080, HTTP)
  /*        -> QA environment (192.168.50.10:443, HTTPS)

Run this script against a MockServer on localhost:1080.
"""

from mockserver import (
    HttpForward,
    HttpRequest,
    MockServerClient,
    Times,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080


def configure_reverse_proxy(client: MockServerClient) -> None:
    """Set up two forwarding rules: REST API to local, everything else to QA."""

    # Rule 1: forward /rest/* to a local backend
    client.when(
        HttpRequest(path="/rest.*"),
        times=Times(unlimited=True),
    ).forward(
        HttpForward(host="127.0.0.1", port=8080, scheme="HTTP")
    )
    print("proxy rule created: /rest/* -> 127.0.0.1:8080 (HTTP)")

    # Rule 2: forward everything else to QA environment
    client.when(
        HttpRequest(path="/.*"),
        times=Times(unlimited=True),
    ).forward(
        HttpForward(host="192.168.50.10", port=443, scheme="HTTPS")
    )
    print("proxy rule created: /* -> 192.168.50.10:443 (HTTPS)")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()
        configure_reverse_proxy(client)
        print("\nReverse proxy configured. MockServer is now forwarding traffic.")
        print("Press Ctrl-C to exit (expectations remain until MockServer is reset).")

        # Keep the script alive so the user can observe the proxy in action.
        # In a real scenario you might not need this -- the expectations persist
        # on the server after the client disconnects.
        try:
            import time
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nExiting.")


if __name__ == "__main__":
    main()
