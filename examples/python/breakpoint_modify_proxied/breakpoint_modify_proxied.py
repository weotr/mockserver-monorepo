#!/usr/bin/env python3
"""MockServer Python client -- breakpoint: modify a proxied exchange.

Demonstrates using a RESPONSE-phase interactive breakpoint to modify a
proxied (forwarded) response before it reaches the caller.  The example
is fully self-contained using a loopback forward (MockServer forwards to
itself via socketAddress), so no external upstream is required.

Flow:
  1. Create a mock "upstream" endpoint:  GET /upstream/greeting -> 200 JSON
  2. Create a forward expectation:       GET /service/greeting  -> forwards
     to /upstream/greeting on the SAME MockServer (socketAddress loopback)
  3. Register a RESPONSE-phase breakpoint on /service/greeting whose handler
     modifies the upstream response before it reaches the caller.
  4. Issue GET /service/greeting and print the modified response.

Prerequisites:
  - MockServer running on localhost:1080 with breakpoint support
  - pip install mockserver-client  (or pip install -e ../../mockserver-client-python)
"""

import json
import urllib.request

from mockserver import (
    Expectation,
    HttpOverrideForwardedRequest,
    HttpRequest,
    HttpResponse,
    MockServerClient,
    SocketAddress,
)

MOCK_HOST = "localhost"
MOCK_PORT = 1080


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        # ------------------------------------------------------------------
        # Step 1: Create a mock "upstream" endpoint
        # ------------------------------------------------------------------
        client.when(
            HttpRequest(method="GET", path="/upstream/greeting")
        ).respond(
            HttpResponse(
                status_code=200,
                body=json.dumps({
                    "message": "Hello from upstream",
                    "source": "original",
                }),
            ).with_header("Content-Type", "application/json")
        )
        print("1. Created upstream mock: GET /upstream/greeting -> 200 JSON")

        # ------------------------------------------------------------------
        # Step 2: Create a loopback forward expectation
        # ------------------------------------------------------------------
        client.upsert(
            Expectation(
                http_request=HttpRequest(method="GET", path="/service/greeting"),
                http_override_forwarded_request=HttpOverrideForwardedRequest(
                    http_request=HttpRequest(
                        path="/upstream/greeting",
                        socket_address=SocketAddress(
                            host=MOCK_HOST,
                            port=MOCK_PORT,
                            scheme="HTTP",
                        ),
                    ),
                ),
            )
        )
        print("2. Created forward expectation: GET /service/greeting -> loopback to /upstream/greeting")

        # ------------------------------------------------------------------
        # Step 3: Register a RESPONSE-phase breakpoint
        # ------------------------------------------------------------------
        def response_handler(request, response):
            """Modify the upstream response before it reaches the caller."""
            print(f"3. Breakpoint fired! Original response body: {response.get('body', '')}")

            try:
                original_body = json.loads(response.get("body", "{}"))
            except (json.JSONDecodeError, TypeError):
                original_body = {}

            # Modify the response
            original_body["source"] = "modified-by-breakpoint"
            original_body["injectedField"] = "this was added by the breakpoint handler"

            return {
                "statusCode": response.get("statusCode", 200),
                "headers": response.get("headers", {}),
                "body": json.dumps(original_body),
            }

        bp_id = client.add_breakpoint(
            HttpRequest(method="GET", path="/service/greeting"),
            ["RESPONSE"],
            response_handler=response_handler,
        )
        print(f"   Breakpoint registered with id: {bp_id}")

        # ------------------------------------------------------------------
        # Step 4: Send a request through the forwarded path
        # ------------------------------------------------------------------
        print("4. Sending GET /service/greeting ...")
        url = f"http://{MOCK_HOST}:{MOCK_PORT}/service/greeting"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as resp:
            status = resp.status
            body = resp.read().decode("utf-8")

        print()
        print("--- Response received ---")
        print(f"Status: {status}")
        print(f"Body:   {body}")

        try:
            parsed = json.loads(body)
            if parsed.get("source") == "modified-by-breakpoint":
                print("\nBreakpoint successfully modified the proxied response!")
        except json.JSONDecodeError:
            pass

        # ------------------------------------------------------------------
        # Clean up
        # ------------------------------------------------------------------
        client.clear_breakpoint_matchers()
        print("\nBreakpoint matchers cleared.")
        client.reset()
        print("MockServer reset.")


if __name__ == "__main__":
    main()
