# A2A Agent Mocking Examples

JSON expectation payloads that mock an [Agent2Agent](https://a2aproject.github.io/A2A/)
(A2A) agent: its discovery document (agent card) and a JSON-RPC task response.

| File | Description |
|------|-------------|
| `a2a_agent_card.json` | Serve the A2A agent card from `/.well-known/agent.json` |
| `a2a_task_response.json` | Answer a `tasks/send` JSON-RPC call with a completed task |

## What it demonstrates

- The **agent card** is a static `httpResponse` served on `GET /.well-known/agent.json`.
  Its body declares `name`, `description`, `version`, `url`, a `capabilities` object
  (`streaming` / `pushNotifications` / `stateTransitionHistory`), and a `skills` array where
  each skill has `id`, `name`, `description`, `tags`, and `examples`.
- The **task response** matches a JSON-RPC `tasks/send` request with a `JSON_RPC` body matcher
  and replies via a Velocity `httpResponseTemplate`. The result carries the task `id`, a
  `status.state` of `completed`, and an `artifacts` array of `parts` (each `{"type":"text","text":...}`).
- `$!{request.jsonRpcRawId}` echoes the inbound JSON-RPC id back with its original type.

## Prerequisites

- A running MockServer instance (default `http://localhost:1080`).
- An A2A client (or `curl`) that fetches the agent card and POSTs JSON-RPC 2.0 task requests
  to `/a2a`.

## Run

```bash
curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
  -d @examples/json/a2a/a2a_agent_card.json

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
  -d @examples/json/a2a/a2a_task_response.json
```

Then exercise the mock:

```bash
curl "${MOCKSERVER_URL:-http://localhost:1080}/.well-known/agent.json"

curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/a2a" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tasks/send","params":{"message":{"parts":[{"type":"text","text":"Translate hello to Spanish"}]}}}'
```

## Expected output

- The agent card request returns the TranslationAgent discovery document.
- The `tasks/send` request returns
  `{"jsonrpc":"2.0","result":{"id":"mock-task-id","status":{"state":"completed"},"artifacts":[{"parts":[{"type":"text","text":"Task completed successfully"}]}]},"id":1}`.
