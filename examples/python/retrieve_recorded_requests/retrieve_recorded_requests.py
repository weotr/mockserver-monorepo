#!/usr/bin/env python3
"""MockServer Python client -- retrieve recorded requests examples.

Each function retrieves requests that MockServer recorded while proxying
or serving expectations.
Run this script against a MockServer on localhost:1080.
"""

import json
import urllib.request

from mockserver import (
    HttpRequest,
    HttpResponse,
    MockServerClient,
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
        pass


def retrieve_all_recorded_requests(client: MockServerClient) -> None:
    """Retrieve all recorded requests (no filter)."""
    recorded = client.retrieve_recorded_requests()
    print(f"all recorded requests ({len(recorded)} total):")
    for r in recorded:
        print(f"  {r.method or 'GET'} {r.path}")


def retrieve_by_path_and_method(client: MockServerClient) -> None:
    """Retrieve recorded requests matching a specific path and method."""
    recorded = client.retrieve_recorded_requests(
        HttpRequest(path="/some/path", method="POST")
    )
    print(f"recorded POST /some/path ({len(recorded)} total):")
    for r in recorded:
        print(f"  {r.method} {r.path}")


def retrieve_recorded_requests_and_responses(client: MockServerClient) -> None:
    """Retrieve recorded request-response pairs."""
    pairs = client.retrieve_recorded_requests_and_responses(
        HttpRequest(path="/some/path")
    )
    print(f"recorded request/response pairs for /some/path ({len(pairs)} total):")
    for pair in pairs:
        req = pair.http_request
        resp = pair.http_response
        req_desc = f"{req.method or 'GET'} {req.path}" if req else "?"
        resp_desc = str(resp.status_code) if resp else "?"
        print(f"  {req_desc} -> {resp_desc}")


def retrieve_active_expectations(client: MockServerClient) -> None:
    """Retrieve currently active expectations."""
    expectations = client.retrieve_active_expectations()
    print(f"active expectations ({len(expectations)} total):")
    for exp in expectations:
        req = exp.http_request
        path = req.path if req else "?"
        print(f"  path={path}  id={exp.id}")


def retrieve_log_messages(client: MockServerClient) -> None:
    """Retrieve log messages from MockServer (useful for debugging)."""
    messages = client.retrieve_log_messages(
        HttpRequest(path="/some/path")
    )
    print(f"log messages for /some/path ({len(messages)} entries)")
    # Print just the first message (they can be long)
    if messages:
        first = messages[0]
        preview = first[:120].replace("\n", " ")
        print(f"  first: {preview}...")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        # Set up expectations and send traffic so there is something to retrieve
        client.when(HttpRequest(path="/some/path")).respond(
            HttpResponse.response("hello")
        )
        client.when(HttpRequest(path="/other/path")).respond(
            HttpResponse.response("world")
        )

        _send_test_request("/some/path")
        _send_test_request("/some/path", method="POST")
        _send_test_request("/other/path")

        # Run retrieval examples
        retrieve_all_recorded_requests(client)
        print()
        retrieve_by_path_and_method(client)
        print()
        retrieve_recorded_requests_and_responses(client)
        print()
        retrieve_active_expectations(client)
        print()
        retrieve_log_messages(client)

        print("\nAll retrieval examples completed.")


if __name__ == "__main__":
    main()
