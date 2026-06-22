#!/usr/bin/env python3
"""MockServer Python client -- stateful-scenario examples.

Runs the five canonical stateful-scenario demonstrations in sequence against a
MockServer, resetting the server before each so every scenario is
self-contained and order-independent. Each scenario exercises the data plane
(plain ``urllib`` HTTP calls) and asserts the observed behaviour, printing a
``PASS: <scenario>`` line on success. The process exits ``0`` only if every
scenario passes, non-zero otherwise.

The server location is discovered from the environment:
  - ``MOCKSERVER_HOST`` (default ``localhost``)
  - ``MOCKSERVER_PORT`` (default ``1080``)
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

from mockserver import (
    CrossProtocolScenario,
    Expectation,
    HttpRequest,
    HttpResponse,
    MockServerClient,
    ResponseMode,
    Times,
)


MOCK_HOST = os.environ.get("MOCKSERVER_HOST", "localhost")
MOCK_PORT = int(os.environ.get("MOCKSERVER_PORT", "1080"))
BASE_URL = f"http://{MOCK_HOST}:{MOCK_PORT}"


def _call(method: str, path: str):
    """Make a data-plane HTTP call, returning (status_code, parsed_json_or_text)."""
    request = urllib.request.Request(url=BASE_URL + path, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            status = response.status
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read().decode("utf-8")
    try:
        body = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        body = raw
    return status, body


def _json_response(status_code: int, payload: dict) -> HttpResponse:
    return (
        HttpResponse()
        .with_status_code(status_code)
        .with_header("Content-Type", "application/json")
        .with_body(json.dumps(payload))
    )


def scenario_state_machine(client: MockServerClient) -> None:
    """1. state_machine -- a login flow driven by scenario state transitions.

    A single login (times=1) moves the ``LoginFlow`` scenario from its default
    ``Started`` state to ``LoggedIn``; ``/profile`` returns 401 before and the
    profile afterwards.
    """
    client.reset()

    client.upsert(
        Expectation(
            http_request=HttpRequest(method="POST", path="/login"),
            http_response=_json_response(200, {"token": "abc123"}),
            scenario_name="LoginFlow",
            scenario_state="Started",
            new_scenario_state="LoggedIn",
            times=Times(remaining_times=1, unlimited=False),
        ),
        Expectation(
            http_request=HttpRequest(method="GET", path="/profile"),
            http_response=_json_response(200, {"name": "Alice"}),
            scenario_name="LoginFlow",
            scenario_state="LoggedIn",
        ),
        Expectation(
            http_request=HttpRequest(method="GET", path="/profile"),
            http_response=_json_response(401, {"error": "Not authenticated"}),
            scenario_name="LoginFlow",
            scenario_state="Started",
        ),
    )

    status, body = _call("GET", "/profile")
    assert status == 401, f"expected 401 before login, got {status}"
    assert body == {"error": "Not authenticated"}, body

    status, body = _call("POST", "/login")
    assert status == 200, f"expected 200 from login, got {status}"
    assert body.get("token") == "abc123", body

    status, body = _call("GET", "/profile")
    assert status == 200, f"expected 200 after login, got {status}"
    assert body == {"name": "Alice"}, body

    print("PASS: state_machine")


def scenario_sequential_cycling(client: MockServerClient) -> None:
    """2. sequential_cycling -- one expectation, several cycling responses.

    A single expectation lists three responses (200, 503, 200) served in
    SEQUENTIAL order; a fourth call cycles back to the first.
    """
    client.reset()

    client.upsert(
        Expectation(
            http_request=HttpRequest(method="GET", path="/api/status"),
            http_responses=[
                _json_response(200, {"status": "ok"}),
                _json_response(503, {"status": "degraded"}),
                _json_response(200, {"status": "ok"}),
            ],
            response_mode=ResponseMode.SEQUENTIAL,
        )
    )

    statuses = [_call("GET", "/api/status")[0] for _ in range(4)]
    assert statuses == [200, 503, 200, 200], statuses

    print("PASS: sequential_cycling")


def scenario_timed_transition(client: MockServerClient) -> None:
    """3. timed_transition -- a timed auto-transition driven by the REST helper.

    ``DeployFlow`` starts in ``Deploying`` and auto-advances to ``Deployed``
    after one second; ``/status`` reflects the active state.
    """
    client.reset()

    client.upsert(
        Expectation(
            http_request=HttpRequest(method="GET", path="/status"),
            http_response=_json_response(200, {"status": "deploying"}),
            scenario_name="DeployFlow",
            scenario_state="Deploying",
        ),
        Expectation(
            http_request=HttpRequest(method="GET", path="/status"),
            http_response=_json_response(200, {"status": "complete"}),
            scenario_name="DeployFlow",
            scenario_state="Deployed",
        ),
    )
    client.scenario("DeployFlow").set(
        "Deploying", transition_after_ms=1000, next_state="Deployed"
    )

    status, body = _call("GET", "/status")
    assert status == 200 and body == {"status": "deploying"}, (status, body)

    time.sleep(1.3)

    status, body = _call("GET", "/status")
    assert status == 200 and body == {"status": "complete"}, (status, body)

    print("PASS: timed_transition")


def scenario_external_trigger(client: MockServerClient) -> None:
    """4. external_trigger -- a state change forced by the REST trigger helper.

    ``HealthFlow`` starts healthy; an external ``trigger("Down")`` flips
    ``/health`` to 503.
    """
    client.reset()

    client.upsert(
        Expectation(
            http_request=HttpRequest(method="GET", path="/health"),
            http_response=_json_response(200, {"status": "healthy"}),
            scenario_name="HealthFlow",
            scenario_state="Started",
        ),
        Expectation(
            http_request=HttpRequest(method="GET", path="/health"),
            http_response=_json_response(503, {"status": "down"}),
            scenario_name="HealthFlow",
            scenario_state="Down",
        ),
    )
    # Establish the starting state so the trigger has a scenario to advance.
    client.scenario("HealthFlow").set("Started")

    status, body = _call("GET", "/health")
    assert status == 200 and body == {"status": "healthy"}, (status, body)

    client.scenario("HealthFlow").trigger("Down")

    status, body = _call("GET", "/health")
    assert status == 503 and body == {"status": "down"}, (status, body)

    print("PASS: external_trigger")


def scenario_cross_protocol(client: MockServerClient) -> None:
    """5. cross_protocol -- one request advances a scenario for another.

    Hitting ``/events`` fires an HTTP_REQUEST cross-protocol trigger that
    advances ``ConnFlow`` to ``Connected``, unlocking ``/api/conn-status``.
    The same mechanism advances scenarios from DNS_QUERY, WEBSOCKET_CONNECT,
    and GRPC_REQUEST events.
    """
    client.reset()

    client.upsert(
        Expectation(
            http_request=HttpRequest(method="GET", path="/events"),
            http_response=_json_response(200, {"status": "subscribed"}),
            cross_protocol_scenarios=[
                CrossProtocolScenario(
                    trigger="HTTP_REQUEST",
                    match_pattern="/events",
                    scenario_name="ConnFlow",
                    target_state="Connected",
                )
            ],
        ),
        Expectation(
            http_request=HttpRequest(method="GET", path="/api/conn-status"),
            http_response=_json_response(200, {"status": "connected"}),
            scenario_name="ConnFlow",
            scenario_state="Connected",
        ),
    )

    status, _ = _call("GET", "/api/conn-status")
    assert status == 404, f"expected 404 before /events, got {status}"

    status, body = _call("GET", "/events")
    assert status == 200, f"expected 200 from /events, got {status}"

    status, body = _call("GET", "/api/conn-status")
    assert status == 200 and body == {"status": "connected"}, (status, body)

    print("PASS: cross_protocol")


def main() -> int:
    scenarios = [
        scenario_state_machine,
        scenario_sequential_cycling,
        scenario_timed_transition,
        scenario_external_trigger,
        scenario_cross_protocol,
    ]
    failed = False
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        for run in scenarios:
            try:
                run(client)
            except AssertionError as error:
                failed = True
                print(f"FAIL: {run.__name__.removeprefix('scenario_')}: {error}")
            except Exception as error:  # noqa: BLE001 - surface any runtime error
                failed = True
                print(f"ERROR: {run.__name__.removeprefix('scenario_')}: {error}")

    if failed:
        print("\nOne or more scenarios failed.")
        return 1
    print("\nAll scenarios passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
