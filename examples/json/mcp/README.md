# MCP Server Mocking Examples

JSON expectation payloads that mock a [Model Context Protocol](https://modelcontextprotocol.io)
(MCP) server's HTTP/JSON-RPC transport. An MCP server is mocked as ordinary MockServer
expectations: a JSON-RPC request matcher plus a Velocity-templated response that echoes the
request id back.

| File | Description |
|------|-------------|
| `mcp_tools_list.json` | Answer the MCP `tools/list` discovery call with one tool |
| `mcp_tool_call.json` | Answer a `tools/call` for the `get_weather` tool |

## What it demonstrates

- An MCP request is matched with a `JSON_RPC` body matcher (`{"type":"JSON_RPC","method":"tools/list"}`),
  which matches a JSON-RPC 2.0 request by its `method`.
- A specific tool invocation is matched with a `JSON_PATH` body matcher on
  `$.method == 'tools/call' && $.params.name == 'get_weather'`.
- The response is an `httpResponseTemplate` of type `VELOCITY` that returns a JSON-RPC 2.0
  envelope (`jsonrpc`/`result`/`id`). `$!{request.jsonRpcRawId}` echoes the inbound request id
  back with its original type (number or string).
- `tools/list` advertises each tool's `name`, `description`, and `inputSchema`.
- `tools/call` returns a `content` array of `{"type":"text","text":...}` parts plus an `isError`
  flag.

## Prerequisites

- A running MockServer instance (default `http://localhost:1080`).
- An MCP client (or `curl`) that speaks JSON-RPC 2.0 over HTTP POST to `/mcp`.

## Run

```bash
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
  -d @examples/json/mcp/mcp_tools_list.json

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
  -d @examples/json/mcp/mcp_tool_call.json
```

Then exercise the mock:

```bash
curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/mcp" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/mcp" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_weather","arguments":{"city":"Paris"}}}'
```

## Expected output

- `tools/list` returns `{"jsonrpc":"2.0","result":{"tools":[{"name":"get_weather",...}]},"id":1}`.
- `tools/call` returns `{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"72F and sunny"}],"isError":false},"id":2}`.
