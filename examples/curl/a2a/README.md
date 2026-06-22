# A2A Agent Mocking (curl)

## What it demonstrates

Mocking an A2A (Agent-to-Agent) agent with raw REST calls. A2A clients first
fetch an agent card from `/.well-known/agent.json`, then send JSON-RPC
`tasks/send` calls to the agent endpoint. These scripts mock both halves.

| Script | Description |
|--------|-------------|
| `agent_card.sh` | Serve the agent card (name, version, capabilities, skills) |
| `task_response.sh` | Return a completed task with a text artifact for `tasks/send` |

## Prerequisites

- A running MockServer instance (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- `curl` installed
- Optionally `export MOCKSERVER_URL=http://localhost:1080` (scripts default to this)

## Run

```bash
./agent_card.sh
./task_response.sh
```

## Expected output

Each script prints the created expectation as JSON ending with
`"times" : { "unlimited" : true }`. A `GET /.well-known/agent.json` then returns
the agent card, and a JSON-RPC `tasks/send` POST to `/a2a` returns a completed
task whose artifact text is `Bonjour`.
