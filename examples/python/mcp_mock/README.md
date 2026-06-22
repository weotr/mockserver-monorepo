# MCP Mock Example

## What it demonstrates

Mocking an MCP (Model Context Protocol) server with the Python client's fluent
MCP mock builder (`mcp_mock(...)`). The builder registers the JSON-RPC 2.0
expectations (initialize, ping, tools/list, tools/call) needed for an MCP client
to discover and invoke a tool over the Streamable HTTP transport. The example
exposes a single `get_weather` tool with a description, input schema, and a
canned text result.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python mcp_mock.py
```

## Expected output

```
expectation created: MCP server 'WeatherServer' with tool 'get_weather' on /mcp

All MCP mock expectations created successfully.
```
