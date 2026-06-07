#!/usr/bin/env python3
"""MockServer Python client -- response action examples.

Each function creates an expectation that returns a canned HTTP response.
Run this script against a MockServer on localhost:1080.
"""

from mockserver import (
    Body,
    ConnectionOptions,
    Delay,
    Expectation,
    HttpRequest,
    HttpResponse,
    MockServerClient,
    Times,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080


def response_with_body_only(client: MockServerClient) -> None:
    """Return a plain-text body for every request (no request matcher)."""
    client.when(
        HttpRequest()
    ).respond(
        HttpResponse.response("some_response_body")
    )
    print("expectation created: response with body only")


def response_with_status_code_and_reason(client: MockServerClient) -> None:
    """Return a custom status code and reason phrase."""
    client.when(
        HttpRequest(method="POST", path="/some/path")
    ).respond(
        HttpResponse(status_code=418, reason_phrase="I'm a teapot")
    )
    print("expectation created: 418 I'm a teapot")


def response_with_header(client: MockServerClient) -> None:
    """Return a response with a custom header."""
    client.when(
        HttpRequest(path="/some/path")
    ).respond(
        HttpResponse.response("some_response_body")
        .with_header("Content-Type", "text/plain")
    )
    print("expectation created: response with header")


def response_with_cookie(client: MockServerClient) -> None:
    """Return a response with a Set-Cookie header."""
    client.when(
        HttpRequest(path="/some/path")
    ).respond(
        HttpResponse.response("some_response_body")
        .with_header("Content-Type", "text/plain")
        .with_cookie("Session", "97d43b1e-fe03-4855-926a-f448eddac32f")
    )
    print("expectation created: response with cookie")


def response_with_json_body(client: MockServerClient) -> None:
    """Return a JSON body."""
    client.when(
        HttpRequest(method="GET", path="/api/user")
    ).respond(
        HttpResponse.response()
        .with_status_code(200)
        .with_header("Content-Type", "application/json")
        .with_body(Body.json({"id": 1, "name": "Alice", "active": True}))
    )
    print("expectation created: JSON body response")


def response_with_delay(client: MockServerClient) -> None:
    """Return a response after a 2-second delay."""
    client.when(
        HttpRequest(path="/slow/path")
    ).respond_with_delay(
        HttpResponse.response("delayed_body"),
        Delay(time_unit="SECONDS", value=2),
    )
    print("expectation created: response with 2s delay")


def respond_differently_for_same_request(client: MockServerClient) -> None:
    """First request returns 200, next two return 204."""
    client.when(
        HttpRequest(path="/some/path"),
        times=Times(remaining_times=1, unlimited=False),
    ).respond(
        HttpResponse(status_code=200)
    )
    client.when(
        HttpRequest(path="/some/path"),
        times=Times(remaining_times=2, unlimited=False),
    ).respond(
        HttpResponse(status_code=204)
    )
    print("expectation created: different responses for same path")


def response_with_connection_options(client: MockServerClient) -> None:
    """Return a response that suppresses content-length and connection headers."""
    client.upsert(
        Expectation(
            http_request=HttpRequest(path="/some/path"),
            http_response=HttpResponse(
                body="some_response_body",
                connection_options=ConnectionOptions(
                    suppress_content_length_header=True,
                    suppress_connection_header=True,
                ),
            ),
        )
    )
    print("expectation created: response with connection options")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        response_with_body_only(client)
        response_with_status_code_and_reason(client)
        response_with_header(client)
        response_with_cookie(client)
        response_with_json_body(client)
        response_with_delay(client)
        respond_differently_for_same_request(client)
        response_with_connection_options(client)

        print("\nAll response-action expectations created successfully.")


if __name__ == "__main__":
    main()
