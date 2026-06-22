#!/usr/bin/env python3
"""MockServer Python client -- MCP (Model Context Protocol) mock example.

Mocks an MCP server that speaks JSON-RPC 2.0 over the Streamable HTTP transport
using the client's fluent MCP mock builder (``mcp_mock(...)``). The builder
registers the initialize/ping/tools-list/tools-call expectations needed for an
MCP client to discover and invoke the tool.

Run this script against a MockServer on localhost:1080.
"""

from mockserver import MockServerClient, mcp_mock


MOCK_HOST = "localhost"
MOCK_PORT = 1080


def mcp_weather_tool(client: MockServerClient) -> None:
    """Mock an MCP server on /mcp exposing a single get_weather tool."""
    mcp_mock("/mcp").with_server_name("WeatherServer").with_tool(
        "get_weather"
    ).with_description("Get the current weather for a city").with_input_schema(
        '{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}'
    ).responding_with(
        "It is sunny and 21C in the requested city."
    ).and_().apply_to(client)
    print("expectation created: MCP server 'WeatherServer' with tool 'get_weather' on /mcp")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        mcp_weather_tool(client)

        print("\nAll MCP mock expectations created successfully.")


if __name__ == "__main__":
    main()
