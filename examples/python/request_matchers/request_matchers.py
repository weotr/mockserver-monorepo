#!/usr/bin/env python3
"""MockServer Python client -- request matcher examples.

Each function creates an expectation that matches requests by path,
query parameters, headers, cookies, or body content.
Run this script against a MockServer on localhost:1080.
"""

from mockserver import (
    Body,
    Expectation,
    HttpRequest,
    HttpResponse,
    KeyToMultiValue,
    MockServerClient,
    TimeToLive,
    Times,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080


# ---------------------------------------------------------------------------
# Path matching
# ---------------------------------------------------------------------------

def match_by_path(client: MockServerClient) -> None:
    """Match requests with an exact path."""
    client.when(
        HttpRequest(path="/some/path")
    ).respond(
        HttpResponse.response("matched by path")
    )
    print("expectation created: match by path")


def match_by_regex_path(client: MockServerClient) -> None:
    """Match requests whose path starts with /some (regex)."""
    client.when(
        HttpRequest(path="/some.*")
    ).respond(
        HttpResponse.response("matched by regex path")
    )
    print("expectation created: match by regex path")


def match_by_path_exactly_twice(client: MockServerClient) -> None:
    """Match only the first two requests to /some/path."""
    client.when(
        HttpRequest(path="/some/path"),
        times=Times(remaining_times=2, unlimited=False),
        time_to_live=TimeToLive.unlimited(),
    ).respond(
        HttpResponse.response("matched exactly twice")
    )
    print("expectation created: match by path exactly twice")


def match_by_path_once_within_60_seconds(client: MockServerClient) -> None:
    """Match a single request within a 60-second window."""
    client.when(
        HttpRequest(path="/some/path"),
        times=Times.exactly(1),
        time_to_live=TimeToLive.exactly(60, "SECONDS"),
    ).respond(
        HttpResponse.response("matched once within 60s")
    )
    print("expectation created: match once within 60s")


def match_by_method(client: MockServerClient) -> None:
    """Match GET requests to /some/path."""
    client.when(
        HttpRequest(method="GET", path="/some/path")
    ).respond(
        HttpResponse.response("matched GET")
    )
    print("expectation created: match by method")


# ---------------------------------------------------------------------------
# Query parameter matching
# ---------------------------------------------------------------------------

def match_by_query_parameter(client: MockServerClient) -> None:
    """Match requests with a specific query parameter value."""
    client.when(
        HttpRequest.request("/some/path")
        .with_query_param("cartId", "055CA455-1DF7-45BB-8535-4F83E7266092")
    ).respond(
        HttpResponse.response("matched by query param")
    )
    print("expectation created: match by query parameter")


def match_by_query_parameter_regex(client: MockServerClient) -> None:
    """Match requests where a query parameter value matches a regex."""
    client.when(
        HttpRequest.request("/some/path")
        .with_query_param("cartId", "[A-Z0-9\\-]+")
    ).respond(
        HttpResponse.response("matched by query param regex")
    )
    print("expectation created: match by query parameter regex")


# ---------------------------------------------------------------------------
# Header matching
# ---------------------------------------------------------------------------

def match_by_headers(client: MockServerClient) -> None:
    """Match requests with specific Accept and Accept-Encoding headers."""
    client.when(
        HttpRequest(method="GET", path="/some/path")
        .with_header("Accept", "application/json")
        .with_header("Accept-Encoding", "gzip, deflate, br")
    ).respond(
        HttpResponse.response("matched by headers")
    )
    print("expectation created: match by headers")


def match_by_header_regex(client: MockServerClient) -> None:
    """Match requests where the Accept header value contains 'gzip'."""
    client.when(
        HttpRequest.request("/some/path")
        .with_header("Accept.*", ".*gzip.*")
    ).respond(
        HttpResponse.response("matched by header regex")
    )
    print("expectation created: match by header regex")


# ---------------------------------------------------------------------------
# Cookie matching
# ---------------------------------------------------------------------------

def match_by_cookies_and_query(client: MockServerClient) -> None:
    """Match GET /view/cart with a specific cartId query param and session cookie."""
    client.when(
        HttpRequest(method="GET", path="/view/cart")
        .with_query_param("cartId", "055CA455-1DF7-45BB-8535-4F83E7266092")
        .with_cookie("session", "4930456C-C718-476F-971F-CB8E047AB349")
    ).respond(
        HttpResponse.response("matched by cookie and query")
    )
    print("expectation created: match by cookies and query parameters")


# ---------------------------------------------------------------------------
# Body matching
# ---------------------------------------------------------------------------

def match_by_json_body(client: MockServerClient) -> None:
    """Match requests whose body is a JSON object (ignoring extra fields)."""
    client.when(
        HttpRequest(path="/api/items")
        .with_body(Body.json({"id": 1, "name": "A green door"}))
    ).respond(
        HttpResponse(status_code=202, body="matched by JSON body")
    )
    print("expectation created: match by JSON body")


def match_by_regex_body(client: MockServerClient) -> None:
    """Match requests whose body starts with a specific string (regex)."""
    client.when(
        HttpRequest()
        .with_body(Body.regex("starts_with_.*"))
    ).respond(
        HttpResponse.response("matched by regex body")
    )
    print("expectation created: match by regex body")


def match_by_xml_body(client: MockServerClient) -> None:
    """Match requests whose body is a specific XML document."""
    xml = (
        "<bookstore>"
        "  <book nationality=\"ITALIAN\" category=\"COOKING\">"
        "    <title lang=\"en\">Everyday Italian</title>"
        "    <author>Giada De Laurentiis</author>"
        "    <year>2005</year>"
        "    <price>30.00</price>"
        "  </book>"
        "</bookstore>"
    )
    client.when(
        HttpRequest()
        .with_body(Body.xml(xml))
    ).respond(
        HttpResponse.response("matched by XML body")
    )
    print("expectation created: match by XML body")


def match_by_string_body(client: MockServerClient) -> None:
    """Match requests whose body is an exact string."""
    client.when(
        HttpRequest(path="/some/path")
        .with_body(Body.exact("exact body content"))
    ).respond(
        HttpResponse.response("matched by exact string body")
    )
    print("expectation created: match by exact string body")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        # Path matchers
        match_by_path(client)
        match_by_regex_path(client)
        match_by_path_exactly_twice(client)
        match_by_path_once_within_60_seconds(client)
        match_by_method(client)

        # Query parameter matchers
        match_by_query_parameter(client)
        match_by_query_parameter_regex(client)

        # Header matchers
        match_by_headers(client)
        match_by_header_regex(client)

        # Cookie matchers
        match_by_cookies_and_query(client)

        # Body matchers
        match_by_json_body(client)
        match_by_regex_body(client)
        match_by_xml_body(client)
        match_by_string_body(client)

        print("\nAll request-matcher expectations created successfully.")


if __name__ == "__main__":
    main()
