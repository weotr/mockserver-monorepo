# Publishing the MockServer MCP server to MCP registries

MockServer ships a built-in **MCP (Model Context Protocol) server** on the Streamable HTTP transport
at `http://localhost:1080/mockserver/mcp` (handler: `McpStreamableHttpHandler`, protocol version
`2025-03-26`). This guide covers listing it on the public MCP registries so AI assistants can
discover it.

## What already exists (no action needed)
- **Client setup docs** — per-assistant config (Cursor, Claude Code, Windsurf, Cline, Continue,
  OpenCode) at `jekyll-www.mock-server.com/mock_server/ai_mcp_setup.html`.
- **Tools reference** — `ai_mcp_tools.html`.
- **`llms.txt`** — enumerates the MCP endpoint + tools for AI crawlers.
- **`server.json`** (repo root) — the registry manifest (this guide publishes it).

## 1. Official MCP registry (`registry.modelcontextprotocol.io`)

Uses the [`mcp-publisher`](https://github.com/modelcontextprotocol/registry) CLI and the repo-root
`server.json`. The server name `io.github.mock-server/mockserver` is verified by GitHub auth as a
member of the `mock-server` org.

```bash
# 1. Install the CLI (Homebrew or from the registry repo releases)
brew install mcp-publisher        # or: go install .../cmd/mcp-publisher@latest

# 2. Validate the manifest against the live schema (authoritative — fix any drift it reports)
mcp-publisher validate ./server.json

# 3. Authenticate (opens GitHub OAuth; must be a mock-server org member to claim io.github.mock-server/*)
mcp-publisher login github

# 4. Publish
mcp-publisher publish
```

Keep `server.json` in sync with each MockServer release — the OCI `identifier` tag
(`docker.io/mockserver/mockserver:<version>`) must point at a released image.

### Prerequisites the registry enforces at publish time (learned the hard way)

`mcp-publisher validate` only checks the JSON schema; the registry applies extra rules on
`mcp-publisher publish`:

1. **Public org membership.** To publish under `io.github.mock-server/*`, the publishing GitHub
   account must be a *public* member of the `mock-server` org. Re-run `mcp-publisher login github`
   after changing membership visibility (the token caches namespaces at login time).
2. **OCI package shape.** For `registryType: oci`: **no** `registryBaseUrl`, **no** package
   `version` field — put the full canonical reference in `identifier`, e.g.
   `docker.io/mockserver/mockserver:6.1.0`.
3. **OCI image ownership label.** The referenced image **must** carry
   `LABEL io.modelcontextprotocol.server.name="io.github.mock-server/mockserver"`. This is set in
   `docker/Dockerfile`, so it ships on images built from that change onward — **but the publish only
   succeeds once a release carrying the label is on Docker Hub** and `server.json`'s `identifier`
   points at that tag. (Images built before the label — e.g. 6.1.0 — are rejected.)

So to finish publishing: ship a labelled image (the next release, or a one-off labelled
rebuild+push of the referenced tag), point `identifier` at it, then `mcp-publisher publish`.

## 2. Other registries

Most of these auto-ingest from the official registry above or crawl GitHub; some accept a manual
submission. Submit/connect the repo (`https://github.com/mock-server/mockserver-monorepo`) or the
published official-registry entry:

| Registry | How |
|----------|-----|
| [Smithery](https://smithery.ai) | Connect the GitHub repo / submit the server; it reads `server.json`. |
| [mcp.so](https://mcp.so) | Submit the server (GitHub URL + endpoint). |
| [PulseMCP](https://www.pulsemcp.com) | Crawls GitHub + the official registry; submit if not auto-listed. |
| [Glama](https://glama.ai/mcp/servers) | Auto-indexes from GitHub / official registry; submit if needed. |

## Notes
- MockServer's MCP is **self-hosted** (you run the container/JAR, then the client connects to the
  local `/mockserver/mcp` URL). The `server.json` `packages` entry therefore points at the
  `mockserver/mockserver` OCI image with a `streamable-http` transport at the localhost endpoint.
- If a registry rejects the `oci` package shape, regenerate the skeleton with `mcp-publisher init`
  and re-apply MockServer's name/description/repository fields — the CLI's schema is authoritative.
