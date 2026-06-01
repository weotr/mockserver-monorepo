#!/usr/bin/env python3
"""MockServer Python client -- forward action examples.

Each function creates an expectation that forwards matching requests to
another server, optionally overriding parts of the request or response.
Run this script against a MockServer on localhost:1080.
"""

from mockserver import (
    Delay,
    Expectation,
    HttpForward,
    HttpOverrideForwardedRequest,
    HttpRequest,
    HttpResponse,
    MockServerClient,
    SocketAddress,
    Times,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080


def forward_in_http(client: MockServerClient) -> None:
    """Forward requests to an HTTP backend."""
    client.when(
        HttpRequest(path="/some/path")
    ).forward(
        HttpForward(host="mock-server.com", port=80, scheme="HTTP")
    )
    print("expectation created: forward via HTTP")


def forward_in_https(client: MockServerClient) -> None:
    """Forward requests to an HTTPS backend."""
    client.when(
        HttpRequest(path="/some/path")
    ).forward(
        HttpForward(host="mock-server.com", port=443, scheme="HTTPS")
    )
    print("expectation created: forward via HTTPS")


def forward_with_delay(client: MockServerClient) -> None:
    """Forward after a 5-second delay."""
    client.when(
        HttpRequest(path="/delayed/forward")
    ).forward_with_delay(
        HttpForward(host="mock-server.com", port=443, scheme="HTTPS"),
        Delay(time_unit="SECONDS", value=5),
    )
    print("expectation created: forward with delay")


def forward_overridden_request(client: MockServerClient) -> None:
    """Forward the request but change the path and Host header."""
    client.when(
        HttpRequest(path="/some/path")
    ).forward(
        HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/some/other/path")
            .with_header("Host", "target.host.com"),
        )
    )
    print("expectation created: forward with overridden request")


def forward_overridden_request_and_response(client: MockServerClient) -> None:
    """Forward the request and override the response body."""
    client.when(
        HttpRequest(path="/some/path")
    ).forward(
        HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/some/other/path")
            .with_header("Host", "target.host.com"),
            http_response=HttpResponse(body="some_overridden_body"),
        )
    )
    print("expectation created: forward with overridden request and response")


def forward_with_socket_address(client: MockServerClient) -> None:
    """Forward to a specific host:port using socketAddress (separate from Host header)."""
    client.when(
        HttpRequest(path="/some/path")
    ).forward(
        HttpOverrideForwardedRequest(
            http_request=HttpRequest(
                path="/some/other/path",
                socket_address=SocketAddress(
                    host="target.host.com",
                    port=1234,
                    scheme="HTTPS",
                ),
            ).with_header("Host", "any.host.com"),
        )
    )
    print("expectation created: forward with explicit socket address")


def forward_with_request_modifier(client: MockServerClient) -> None:
    """Forward and modify the request using a requestModifier dict."""
    client.upsert(
        Expectation(
            http_request=HttpRequest(path="/some/path"),
            http_override_forwarded_request=HttpOverrideForwardedRequest(
                http_request=HttpRequest()
                .with_header("Host", "target.host.com"),
                request_modifier={
                    "headers": {
                        "add": {"X-Forwarded-By": ["mockserver"]},
                        "remove": ["X-Internal-Only"],
                    },
                    "path": {
                        "regex": "^/some/(.+)$",
                        "substitution": "/api/$1",
                    },
                },
            ),
        )
    )
    print("expectation created: forward with request modifier")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        forward_in_http(client)
        forward_in_https(client)
        forward_with_delay(client)
        forward_overridden_request(client)
        forward_overridden_request_and_response(client)
        forward_with_socket_address(client)
        forward_with_request_modifier(client)

        print("\nAll forward-action expectations created successfully.")


if __name__ == "__main__":
    main()
