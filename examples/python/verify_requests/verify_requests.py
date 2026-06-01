#!/usr/bin/env python3
"""MockServer Python client -- verify requests examples.

Each function verifies that MockServer received specific requests.
Run this script against a MockServer on localhost:1080 that has already
received some traffic (or create expectations and send test requests first).
"""

import urllib.request

from mockserver import (
    HttpRequest,
    HttpResponse,
    MockServerClient,
    MockServerVerificationError,
    VerificationTimes,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080


def _send_test_request(path: str, method: str = "GET") -> None:
    """Send a simple request to MockServer to generate recorded traffic."""
    url = f"http://{MOCK_HOST}:{MOCK_PORT}{path}"
    req = urllib.request.Request(url, method=method)
    try:
        urllib.request.urlopen(req, timeout=5)
    except Exception:
        pass  # we don't care about the response


def verify_at_least(client: MockServerClient) -> None:
    """Verify that /some/path was called at least 2 times."""
    try:
        client.verify(
            HttpRequest(path="/some/path"),
            times=VerificationTimes.at_least(2),
        )
        print("verification passed: /some/path received at least 2 times")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def verify_at_most(client: MockServerClient) -> None:
    """Verify that /some/path was called at most 5 times."""
    try:
        client.verify(
            HttpRequest(path="/some/path"),
            times=VerificationTimes.at_most(5),
        )
        print("verification passed: /some/path received at most 5 times")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def verify_exactly(client: MockServerClient) -> None:
    """Verify that /some/path was called exactly 3 times."""
    try:
        client.verify(
            HttpRequest(path="/some/path"),
            times=VerificationTimes.exactly(3),
        )
        print("verification passed: /some/path received exactly 3 times")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def verify_between(client: MockServerClient) -> None:
    """Verify that /some/path was called between 1 and 5 times."""
    try:
        client.verify(
            HttpRequest(path="/some/path"),
            times=VerificationTimes.between(1, 5),
        )
        print("verification passed: /some/path received between 1 and 5 times")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def verify_once(client: MockServerClient) -> None:
    """Verify that /unique/path was called exactly once."""
    try:
        client.verify(
            HttpRequest(path="/unique/path"),
            times=VerificationTimes.once(),
        )
        print("verification passed: /unique/path received exactly once")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def verify_zero_interactions(client: MockServerClient) -> None:
    """Verify that MockServer received no requests at all (after a reset)."""
    client.reset()
    try:
        client.verify_zero_interactions()
        print("verification passed: zero interactions")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def verify_sequence(client: MockServerClient) -> None:
    """Verify that requests arrived in a specific order."""
    # Set up expectations and send requests in order
    client.reset()
    client.when(HttpRequest(path="/first")).respond(HttpResponse(status_code=200))
    client.when(HttpRequest(path="/second")).respond(HttpResponse(status_code=200))
    client.when(HttpRequest(path="/third")).respond(HttpResponse(status_code=200))

    _send_test_request("/first")
    _send_test_request("/second")
    _send_test_request("/third")

    try:
        client.verify_sequence(
            HttpRequest(path="/first"),
            HttpRequest(path="/second"),
            HttpRequest(path="/third"),
        )
        print("verification passed: requests arrived in sequence")
    except MockServerVerificationError as e:
        print(f"verification failed: {e}")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        # Set up an expectation and send a few requests to have something to verify
        client.reset()
        client.when(HttpRequest(path="/some/path")).respond(
            HttpResponse(status_code=200)
        )
        client.when(HttpRequest(path="/unique/path")).respond(
            HttpResponse(status_code=200)
        )

        # Generate traffic
        for _ in range(3):
            _send_test_request("/some/path")
        _send_test_request("/unique/path")

        # Run verifications
        verify_at_least(client)
        verify_at_most(client)
        verify_exactly(client)
        verify_between(client)
        verify_once(client)
        verify_zero_interactions(client)
        verify_sequence(client)

        print("\nAll verification examples completed.")


if __name__ == "__main__":
    main()
