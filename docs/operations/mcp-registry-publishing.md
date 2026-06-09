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

## 1. Official MCP registry (`registry.modelcontextprotocol.io`) — automated in the release pipeline

**TL;DR:** publishing is wired into the release pipeline (`scripts/release/components/mcp.sh`, the
`:robot_face: MCP Registry` Buildkite step). It runs **non-interactively** because the namespace is
`com.mock-server/mockserver` — a **DNS-verified** namespace derived from the `mock-server.com`
domain we own — authenticated with an ed25519 key in Secrets Manager. No human/browser step.

> **Why DNS, not GitHub?** The old namespace `io.github.mock-server/*` authenticates via interactive
> GitHub OAuth (`mcp-publisher login github` opens a browser) and can't run headless in Buildkite
> (which, unlike GitHub Actions, has no GitHub OIDC). A DNS-verified namespace authenticates with a
> stored private key, so the release publishes hands-off.

### One-time setup — ✅ DONE (2026-06-07), recorded here for reference/rotation

The ed25519 keypair was generated and the namespace verified end-to-end
(`mcp-publisher login dns --domain mock-server.com` → "✓ Successfully logged in"). What was done:

1. **Keypair** generated with `openssl genpkey -algorithm ed25519`; the 32-byte seed (hex) is the
   `-private-key`, the 32-byte public key (base64) goes in the TXT record. The exact proof record is
   what `mcp-publisher login dns` prints: `v=MCPv1; k=ed25519; p=<base64-public-key>`.
2. **DNS TXT record** — managed in Terraform: `terraform/website/mcp-dns.tf` manages the
   `mock-server.com` apex TXT record set with BOTH the existing Google Search Console value AND the
   MCP proof (`allow_overwrite = true` adopts the previously-unmanaged record). The public key is set
   in `terraform/website/terraform.tfvars` as `mcp_dns_public_key`. Applied to Route 53.
   Verify: `dig +short TXT mock-server.com | grep MCPv1`.
3. **Private key** stored in Secrets Manager: `mockserver-release/mcp-dns-key` (key `private_key`,
   build account, eu-west-2). The release-queue agent is granted `GetSecretValue` + `DescribeSecret`
   on it via `terraform/buildkite-agents/build-secrets.tf` (applied).
4. **CLI**: `mcp.sh` downloads `mcp-publisher` if absent (soft-fails on failure); set
   `MCP_PUBLISHER_VERSION` / `MCP_PUBLISHER_SHA256` on the agent to pin+verify.

The next release publishes automatically. **To rotate the key:** regenerate, update
`mcp_dns_public_key` in tfvars + `terraform apply` (website), and overwrite the secret value.

### What `mcp.sh` does each release
1. Rewrites `server.json` → `version`, `packages[0].identifier`
   (`docker.io/mockserver/mockserver:<version>`), and `name` (`com.mock-server/mockserver`).
2. `mcp-publisher validate ./server.json` (schema check).
3. `mcp-publisher login dns --domain mock-server.com --private-key <secret>`.
4. `mcp-publisher publish`, then commits the synced `server.json`.

### Registry preconditions it satisfies (enforced server-side)
1. **OCI package shape.** For `registryType: oci`: **no** `registryBaseUrl`, **no** package
   `version` field — the full canonical reference goes in `identifier`.
2. **OCI image ownership label.** The referenced image must carry
   `LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"` (set in
   `docker/Dockerfile`). The Docker Image step pushes that label before the MCP step runs, so on a
   full release the label is live on Docker Hub when publish fires.

`verify.sh` does a soft post-release check that the registry lists the new version.

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
