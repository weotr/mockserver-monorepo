# MCP Mock Examples

## What it demonstrates

Mocking an MCP (Model Context Protocol) server with the Node client's fluent MCP
mock builder (`client.mcpMock(...)`). The builder registers the JSON-RPC 2.0
expectations (initialize, ping, tools/list, tools/call) needed for an MCP client
to discover and invoke a tool over the Streamable HTTP transport. The example
exposes a single `get_weather` tool with a description, input schema, and a
canned text result.

## Prerequisites

- Node.js
- `npm install` (installs `mockserver-client`)
- MockServer running on `localhost:1080`

## Run

```bash
npm install
node mcp_mock.js
```

## Expected output

```
expectation created: MCP server 'WeatherServer' with tool 'get_weather' on /mcp
```
