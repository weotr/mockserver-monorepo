# MCP Server Mocking (curl)

## What it demonstrates

Mocking an MCP (Model Context Protocol) server over HTTP + JSON-RPC with raw
REST calls. Requests are matched by JSON-RPC method using the `JSON_RPC` body
matcher, and responses are built with a Velocity template that echoes the
client's JSON-RPC id back via `$!{request.jsonRpcRawId}`.

| Script | Description |
|--------|-------------|
| `mcp_tool.sh` | Advertise a tool (`tools/list`) and return its result (`tools/call`) |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./mcp_tool.sh
```

## Expected output

The script prints the two created expectations as JSON (one for `tools/list`,
one for `tools/call`), each ending with `"times" : { "unlimited" : true }`.
A subsequent JSON-RPC `tools/list` POST to `/mcp` then returns the advertised
`get_weather` tool.
