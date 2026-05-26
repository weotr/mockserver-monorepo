/**
 * Thin fetch wrapper around `POST /mockserver/mcp` that builds the
 * JSON-RPC 2.0 `tools/call` envelope and parses the response.
 *
 * This is used by both the CaptureAsMockDialog (mock_llm_completion)
 * and the ConversationWizard (create_llm_conversation) to register
 * expectations via the MCP transport instead of the raw REST API.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface McpToolCallResult {
  ok: boolean;
  /** Parsed `result` object on success. */
  result?: Record<string, unknown>;
  /** Parsed error object or string on failure. */
  error?: unknown;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Call an MCP tool via MockServer's `POST /mockserver/mcp` endpoint.
 *
 * @param baseUrl  The MockServer base URL including protocol, host, and port
 *                 (e.g. `http://127.0.0.1:1080`).
 * @param toolName The MCP tool to invoke (e.g. `mock_llm_completion`).
 * @param args     The tool arguments as a plain object.
 * @returns Parsed result.
 */
export async function callMcpTool(
  baseUrl: string,
  toolName: string,
  args: Record<string, unknown>,
): Promise<McpToolCallResult> {
  const envelope = {
    jsonrpc: '2.0',
    id: Date.now(),
    method: 'tools/call',
    params: {
      name: toolName,
      arguments: args,
    },
  };

  const response = await fetch(`${baseUrl}/mockserver/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(envelope),
  });

  if (!response.ok) {
    return {
      ok: false,
      error: `HTTP ${response.status}: ${response.statusText}`,
    };
  }

  const body = await response.json();

  // JSON-RPC error
  if (body.error) {
    return { ok: false, error: body.error };
  }

  // The MCP transport wraps tool results in `result.content[0].text` as a
  // JSON-encoded string.  Attempt to unwrap it, but fall back to the raw
  // `result` object if the shape differs.
  const rpcResult = body.result;
  if (rpcResult && Array.isArray(rpcResult.content) && rpcResult.content.length > 0) {
    const text = rpcResult.content[0]?.text;
    if (typeof text === 'string') {
      try {
        const parsed = JSON.parse(text) as Record<string, unknown>;
        if (parsed.error) {
          return { ok: false, error: parsed };
        }
        return { ok: true, result: parsed };
      } catch {
        // Not valid JSON — treat as opaque success with raw text
        return { ok: true, result: { text } };
      }
    }
  }

  // Fallback: treat the entire result object as the tool result
  if (rpcResult) {
    if (typeof rpcResult === 'object' && rpcResult.error) {
      return { ok: false, error: rpcResult };
    }
    return { ok: true, result: rpcResult };
  }

  return { ok: true, result: {} };
}

/**
 * Build the MockServer base URL from connection parameters.
 */
export function buildBaseUrl(params: { host: string; port: string; secure: boolean }): string {
  const protocol = params.secure ? 'https' : 'http';
  return `${protocol}://${params.host}:${params.port}`;
}
