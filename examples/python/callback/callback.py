#!/usr/bin/env python3
"""MockServer Python client -- callback examples.

Demonstrates the two kinds of MockServer callback from the Python client,
resetting the server before each so every example is self-contained and
order-independent. Each example asserts the observed behaviour and prints a
``PASS: <example>`` line on success. The process exits ``0`` only if every
example passes, non-zero otherwise.

  1. object_callback -- an *object* (closure) callback. The client opens a
     callback WebSocket, the server hands it a clientId, and on every match the
     server streams the request to the closure which writes the response in
     Python. Here the closure derives the response dynamically from the request
     (echoing a path parameter and a header), proving the response is computed
     per-request rather than canned.

  2. class_callback -- a *class* callback (REST-only, no WebSocket). The
     expectation references a server-side class by name. We assert the server
     ACCEPTS the expectation (the class need not exist to validate the wire
     shape -- the server stores it and returns the persisted expectation).

The server location is discovered from the environment:
  - ``MOCKSERVER_HOST`` (default ``localhost``)
  - ``MOCKSERVER_PORT`` (default ``1080``)
"""

import json
import os
import sys
import urllib.error
import urllib.request

from mockserver import (
    HttpClassCallback,
    HttpRequest,
    HttpResponse,
    MockServerClient,
)


MOCK_HOST = os.environ.get("MOCKSERVER_HOST", "localhost")
MOCK_PORT = int(os.environ.get("MOCKSERVER_PORT", "1080"))
BASE_URL = f"http://{MOCK_HOST}:{MOCK_PORT}"


def _call(method: str, path: str, headers: dict | None = None):
    """Make a data-plane HTTP call, returning (status, headers, parsed_body)."""
    request = urllib.request.Request(url=BASE_URL + path, method=method)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request) as response:
            status = response.status
            resp_headers = dict(response.headers.items())
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status = error.code
        resp_headers = dict(error.headers.items())
        raw = error.read().decode("utf-8")
    try:
        body = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        body = raw
    return status, resp_headers, body


def _header_value(request: HttpRequest, name: str) -> str | None:
    """Return the first value of header *name* on *request* (case-insensitive)."""
    for header in request.headers or []:
        if header.name.lower() == name.lower():
            return header.values[0] if header.values else None
    return None


def example_object_callback(client: MockServerClient) -> None:
    """1. object_callback -- a closure that derives the response from the request.

    The closure inspects the incoming request and builds a response on the fly:
    it echoes the request path and a request header back to the caller. Because
    the response is computed from the request, the same expectation produces
    different responses for different requests.
    """
    client.reset()

    def handler(request: HttpRequest) -> HttpResponse:
        # Derive the response dynamically from the request.
        who = _header_value(request, "X-Who") or "anonymous"
        payload = {
            "echoedPath": request.path,
            "echoedMethod": request.method,
            "greeting": f"hello {who}",
        }
        return (
            HttpResponse()
            .with_status_code(200)
            .with_header("Content-Type", "application/json")
            .with_header("X-Handled-By", "python-object-callback")
            .with_body(json.dumps(payload))
        )

    client.mock_with_callback(
        HttpRequest(method="GET", path="/dynamic"),
        handler,
    )

    status, headers, body = _call("GET", "/dynamic", headers={"X-Who": "alice"})
    assert status == 200, f"expected 200, got {status}"
    assert headers.get("X-Handled-By") == "python-object-callback", headers
    assert body == {
        "echoedPath": "/dynamic",
        "echoedMethod": "GET",
        "greeting": "hello alice",
    }, body

    # A different request yields a different (dynamically computed) response.
    status, _, body = _call("GET", "/dynamic", headers={"X-Who": "bob"})
    assert status == 200, f"expected 200, got {status}"
    assert body["greeting"] == "hello bob", body

    print("PASS: object_callback")


def example_class_callback(client: MockServerClient) -> None:
    """2. class_callback -- a server-side class callback (REST-only).

    The expectation references a callback class by fully-qualified name. The
    class itself need not exist for the server to ACCEPT and persist the
    expectation; we assert the upsert succeeds and round-trips the wire shape.
    """
    client.reset()

    # Both the typed builder and the fully-qualified-name string shorthand work.
    created = (
        client.when(HttpRequest(method="GET", path="/via-class"))
        .respond_with_class_callback(
            HttpClassCallback(callback_class="com.example.MyResponseCallback")
        )
    )
    assert created, "server returned no expectation for the class callback"
    persisted = created[0]
    assert (
        persisted.http_response_class_callback is not None
        and persisted.http_response_class_callback.callback_class
        == "com.example.MyResponseCallback"
    ), persisted.to_dict()

    # The string shorthand on a forward class callback is also accepted.
    forwarded = (
        client.when(HttpRequest(method="GET", path="/via-forward-class"))
        .forward_with_class_callback("com.example.MyForwardCallback")
    )
    assert forwarded, "server returned no expectation for the forward class callback"
    assert (
        forwarded[0].http_forward_class_callback.callback_class
        == "com.example.MyForwardCallback"
    ), forwarded[0].to_dict()

    print("PASS: class_callback")


def main() -> int:
    examples = [
        example_object_callback,
        example_class_callback,
    ]
    failed = False
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        for run in examples:
            try:
                run(client)
            except AssertionError as error:
                failed = True
                print(f"FAIL: {run.__name__.removeprefix('example_')}: {error}")
            except Exception as error:  # noqa: BLE001 - surface any runtime error
                failed = True
                print(f"ERROR: {run.__name__.removeprefix('example_')}: {error}")

    if failed:
        print("\nOne or more callback examples failed.")
        return 1
    print("\nAll callback examples passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
