// Pure, `vscode`-free helpers for the agent-run call graph (Phase 6). Ported from
// the dashboard's mockserver-ui/src/lib/callGraph.ts so the editor renders the
// SAME graph the dashboard draws. The graph is fetched via the MCP
// `explain_agent_run` tool over `POST /mockserver/mcp` (see fetchAgentCallGraph).

import type { FetchLike } from "./mockServerClient";

export interface CallGraphNode {
    id: string;
    kind: string; // USER / ASSISTANT / SYSTEM / TOOL / TOOL_CALL
    label: string;
}

export interface CallGraphEdge {
    from: string;
    to: string;
    kind: string; // NEXT / INVOKES / RESULT
}

export interface CallGraph {
    nodes: CallGraphNode[];
    edges: CallGraphEdge[];
}

/** Parse a `callGraph` object (e.g. from an explain_agent_run result) defensively. */
export function parseCallGraph(raw: unknown): CallGraph | null {
    if (raw == null || typeof raw !== "object") {
        return null;
    }
    const r = raw as Record<string, unknown>;
    const rawNodes = Array.isArray(r["nodes"]) ? (r["nodes"] as unknown[]) : [];
    const rawEdges = Array.isArray(r["edges"]) ? (r["edges"] as unknown[]) : [];
    const nodes: CallGraphNode[] = rawNodes
        .map((n) => n as Record<string, unknown>)
        .filter((n) => typeof n["id"] === "string")
        .map((n) => ({
            id: n["id"] as string,
            kind: typeof n["kind"] === "string" ? (n["kind"] as string) : "UNKNOWN",
            label: typeof n["label"] === "string" ? (n["label"] as string) : "",
        }));
    const edges: CallGraphEdge[] = rawEdges
        .map((e) => e as Record<string, unknown>)
        .filter((e) => typeof e["from"] === "string" && typeof e["to"] === "string")
        .map((e) => ({
            from: e["from"] as string,
            to: e["to"] as string,
            kind: typeof e["kind"] === "string" ? (e["kind"] as string) : "NEXT",
        }));
    return { nodes, edges };
}

function escapeMermaid(label: string): string {
    // Mermaid quoted labels: avoid quotes/newlines (GitHub renderer rejects HTML).
    return label.replace(/"/g, "'").replace(/\s+/g, " ").trim().substring(0, 60);
}

const TOOL_CALL = "TOOL_CALL";

/** Render the graph as a Mermaid `flowchart TD` string (matches the dashboard). */
export function toMermaid(graph: CallGraph): string {
    const lines: string[] = ["flowchart TD"];
    for (const node of graph.nodes) {
        const shape = node.kind === TOOL_CALL ? ["([", "])"] : ['["', '"]'];
        const text =
            node.kind === TOOL_CALL
                ? escapeMermaid(node.label)
                : escapeMermaid(node.kind + ": " + node.label);
        lines.push(`  ${node.id}${shape[0]}${text}${shape[1]}`);
    }
    for (const edge of graph.edges) {
        lines.push(`  ${edge.from} -->|${edge.kind}| ${edge.to}`);
    }
    return lines.join("\n");
}

// ---------------------------------------------------------------------------
// MCP transport for explain_agent_run. The call graph is only exposed through
// the MCP tool, so we run the same minimal initialize → tools/call flow the
// dashboard's mcpClient.ts uses. Kept `vscode`-free and injectable for tests.
// ---------------------------------------------------------------------------

/** A FetchLike that also surfaces a response header (for the Mcp-Session-Id). */
export type FetchWithHeaders = (
    input: string,
    init?: {
        method?: string;
        headers?: Record<string, string>;
        body?: string | Uint8Array;
    }
) => Promise<{
    ok: boolean;
    status: number;
    text(): Promise<string>;
    header(name: string): string | null;
}>;

const MCP_PROTOCOL_VERSION = "2025-03-26";

/**
 * Perform the MCP initialize + notifications/initialized handshake against
 * `POST /mockserver/mcp` and return the session id. Throws on failure.
 */
async function initMcpSession(baseUrl: string, fetchFn: FetchWithHeaders): Promise<string> {
    const initRes = await fetchFn(`${baseUrl}/mockserver/mcp`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            jsonrpc: "2.0",
            id: Date.now(),
            method: "initialize",
            params: {
                protocolVersion: MCP_PROTOCOL_VERSION,
                capabilities: {},
                clientInfo: { name: "mockserver-vscode", version: "1" },
            },
        }),
    });
    if (!initRes.ok) {
        throw new Error(`MCP initialize failed: HTTP ${initRes.status}`);
    }
    const sessionId = initRes.header("Mcp-Session-Id");
    if (!sessionId) {
        throw new Error("MCP initialize returned no Mcp-Session-Id header");
    }
    await fetchFn(`${baseUrl}/mockserver/mcp`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Mcp-Session-Id": sessionId },
        body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    });
    return sessionId;
}

/**
 * Parse a JSON-RPC `tools/call` response body into the tool's result object. The
 * MCP transport wraps the result in `result.content[0].text` as JSON. Falls back
 * to the raw `result` object. Returns `null` on an error envelope. Pure.
 */
export function parseMcpToolResult(body: string): Record<string, unknown> | null {
    let parsed: { result?: unknown; error?: unknown };
    try {
        parsed = JSON.parse(body);
    } catch {
        return null;
    }
    if (parsed.error) {
        return null;
    }
    const rpcResult = parsed.result as Record<string, unknown> | undefined;
    if (rpcResult && Array.isArray(rpcResult["content"]) && (rpcResult["content"] as unknown[]).length > 0) {
        const first = (rpcResult["content"] as Array<Record<string, unknown>>)[0];
        const text = first ? first["text"] : undefined;
        if (typeof text === "string") {
            try {
                return JSON.parse(text) as Record<string, unknown>;
            } catch {
                return { text };
            }
        }
    }
    return rpcResult ?? null;
}

/**
 * Fetch the agent-run call graph via the MCP `explain_agent_run` tool. `args` are
 * the tool arguments (e.g. `{ sessionId }` or correlation filters). Returns the
 * parsed {@link CallGraph}, or `null` when the server returns no `callGraph`.
 * Throws (with a clear message) when the MCP transport itself fails.
 */
export async function fetchAgentCallGraph(
    baseUrl: string,
    args: Record<string, unknown>,
    fetchFn: FetchWithHeaders
): Promise<CallGraph | null> {
    const sessionId = await initMcpSession(baseUrl, fetchFn);
    const res = await fetchFn(`${baseUrl}/mockserver/mcp`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Mcp-Session-Id": sessionId },
        body: JSON.stringify({
            jsonrpc: "2.0",
            id: Date.now(),
            method: "tools/call",
            params: { name: "explain_agent_run", arguments: args },
        }),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const result = parseMcpToolResult(await res.text());
    if (!result) {
        return null;
    }
    return parseCallGraph(result["callGraph"]);
}
