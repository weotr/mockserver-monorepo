/**
 * Thin fetch wrapper around `POST /mockserver/mcp` that builds the
 * JSON-RPC 2.0 `tools/call` envelope and parses the response.
 *
 * MockServer enforces the MCP session lifecycle: every call besides
 * `initialize` requires an `Mcp-Session-Id` header obtained from a prior
 * `initialize` response. We perform the handshake lazily on first use and
 * cache the session ID per baseUrl. If the cached session expires we
 * detect the "Missing or invalid Mcp-Session-Id" error and reinitialize
 * once before failing.
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
// Session cache (per baseUrl)
// ---------------------------------------------------------------------------

const sessionCache = new Map<string, string>();
// Coalesce concurrent handshakes for the same baseUrl. Without this, two
// callMcpTool calls arriving before the first handshake completes would each
// observe an empty cache and start their own initialize → 2 sessions on the
// server with only one being remembered. The promise lives in the map until
// it resolves, after which the cached session is used.
const initInFlight = new Map<string, Promise<string>>();

/**
 * Perform the MCP initialize + notifications/initialized handshake and
 * return the session id. The result is cached per baseUrl.
 */
async function initializeSession(baseUrl: string): Promise<string> {
  const initEnvelope = {
    jsonrpc: '2.0',
    id: Date.now(),
    method: 'initialize',
    params: {
      // Match the version declared by McpStreamableHttpHandler.PROTOCOL_VERSION.
      protocolVersion: '2025-03-26',
      capabilities: {},
      clientInfo: { name: 'mockserver-dashboard', version: '1' },
    },
  };

  const initRes = await fetch(`${baseUrl}/mockserver/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(initEnvelope),
  });
  if (!initRes.ok) {
    throw new Error(`MCP initialize failed: HTTP ${initRes.status} ${initRes.statusText}`);
  }
  const sessionId = initRes.headers.get('Mcp-Session-Id');
  if (!sessionId) {
    throw new Error('MCP initialize returned no Mcp-Session-Id header');
  }

  // Complete the handshake. Notifications have no id and no response body
  // is consumed (the server returns 202 Accepted).
  await fetch(`${baseUrl}/mockserver/mcp`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Mcp-Session-Id': sessionId,
    },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  });

  sessionCache.set(baseUrl, sessionId);
  return sessionId;
}

async function ensureSession(baseUrl: string): Promise<string> {
  const cached = sessionCache.get(baseUrl);
  if (cached) return cached;
  const inFlight = initInFlight.get(baseUrl);
  if (inFlight) return inFlight;
  const promise = initializeSession(baseUrl).finally(() => initInFlight.delete(baseUrl));
  initInFlight.set(baseUrl, promise);
  return promise;
}

function isExpiredSessionError(error: unknown): boolean {
  if (typeof error === 'string') {
    return error.includes('Mcp-Session-Id');
  }
  if (typeof error === 'object' && error !== null) {
    const message = (error as Record<string, unknown>)['message'];
    if (typeof message === 'string' && message.includes('Mcp-Session-Id')) return true;
  }
  return false;
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
  return callMcpToolOnce(baseUrl, toolName, args, /*retried*/ false);
}

async function callMcpToolOnce(
  baseUrl: string,
  toolName: string,
  args: Record<string, unknown>,
  retried: boolean,
): Promise<McpToolCallResult> {
  let sessionId: string;
  try {
    sessionId = await ensureSession(baseUrl);
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }

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
    headers: {
      'Content-Type': 'application/json',
      'Mcp-Session-Id': sessionId,
    },
    body: JSON.stringify(envelope),
  });

  if (!response.ok) {
    // 404 means the cached session was deleted server-side; retry once after
    // reinitialising. 400 is intentionally NOT retried because it covers
    // malformed requests as well — retrying would mask client-side bugs.
    if (!retried && response.status === 404) {
      sessionCache.delete(baseUrl);
      return callMcpToolOnce(baseUrl, toolName, args, true);
    }
    return {
      ok: false,
      error: `HTTP ${response.status}: ${response.statusText}`,
    };
  }

  const body = await response.json();

  // JSON-RPC error envelope
  if (body.error) {
    if (!retried && isExpiredSessionError(body.error)) {
      sessionCache.delete(baseUrl);
      return callMcpToolOnce(baseUrl, toolName, args, true);
    }
    return { ok: false, error: body.error };
  }

  // The MCP transport wraps tool results in `result.content[0].text` as a
  // JSON-encoded string. Attempt to unwrap it, but fall back to the raw
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
 * Build the MockServer base URL from connection parameters, including the optional base/context
 * path so REST calls reach the server when the dashboard is served under a reverse-proxy path.
 */
export function buildBaseUrl(params: { host: string; port: string; secure: boolean; basePath?: string }): string {
  const protocol = params.secure ? 'https' : 'http';
  return `${protocol}://${params.host}:${params.port}${params.basePath ?? ''}`;
}

/**
 * Reset cached MCP sessions. Exposed for tests / manual reconnect flows.
 */
export function _clearMcpSessionCache(): void {
  sessionCache.clear();
  initInFlight.clear();
}
