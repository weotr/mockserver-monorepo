import { describe, it, expect, vi, beforeEach } from 'vitest';
import { callMcpTool, buildBaseUrl, _clearMcpSessionCache } from '../lib/mcpClient';

// ---------------------------------------------------------------------------
// Test helpers — mock the MCP handshake (initialize + notifications) so the
// test responses can focus on what the tool call returns.
// ---------------------------------------------------------------------------

interface MockResponse {
  ok: boolean;
  status?: number;
  statusText?: string;
  headers?: { get: (name: string) => string | null };
  json?: () => Promise<unknown>;
}

function initResponse(sessionId = 'test-session-id'): MockResponse {
  return {
    ok: true,
    status: 200,
    headers: {
      get: (name: string) => (name.toLowerCase() === 'mcp-session-id' ? sessionId : null),
    },
    json: () => Promise.resolve({ jsonrpc: '2.0', id: 1, result: {} }),
  };
}

function notificationAck(): MockResponse {
  return {
    ok: true,
    status: 202,
    statusText: 'Accepted',
    headers: { get: () => null },
    json: () => Promise.resolve({}),
  };
}

function sequenceFetch(responses: MockResponse[]) {
  const fn = vi.fn();
  responses.forEach((r) => fn.mockResolvedValueOnce(r));
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('buildBaseUrl', () => {
  it('builds http URL', () => {
    expect(buildBaseUrl({ host: 'localhost', port: '1080', secure: false })).toBe(
      'http://localhost:1080',
    );
  });

  it('builds https URL', () => {
    expect(buildBaseUrl({ host: 'myhost', port: '443', secure: true })).toBe(
      'https://myhost:443',
    );
  });

  it('appends the base/context path when present', () => {
    expect(buildBaseUrl({ host: 'h', port: '443', secure: true, basePath: '/proxy/ms' })).toBe(
      'https://h:443/proxy/ms',
    );
  });

  it('omits the base path when empty or absent', () => {
    expect(buildBaseUrl({ host: 'h', port: '1080', secure: false, basePath: '' })).toBe('http://h:1080');
    expect(buildBaseUrl({ host: 'h', port: '1080', secure: false })).toBe('http://h:1080');
  });
});

describe('callMcpTool', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    _clearMcpSessionCache();
  });

  it('performs initialize handshake then sends tools/call envelope', async () => {
    const toolResponse: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [
            { type: 'text', text: '{"status":"created","count":1}' },
          ],
        },
      }),
    };
    const fetchMock = sequenceFetch([initResponse(), notificationAck(), toolResponse]);

    const result = await callMcpTool(
      'http://localhost:1080',
      'mock_llm_completion',
      { provider: 'ANTHROPIC', path: '/v1/messages', text: 'Hello' },
    );

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ status: 'created', count: 1 });

    // 3 calls total: initialize, notifications/initialized, tools/call
    expect(fetchMock).toHaveBeenCalledTimes(3);

    const initCall = fetchMock.mock.calls[0]!;
    expect(JSON.parse(initCall[1].body).method).toBe('initialize');

    const notifyCall = fetchMock.mock.calls[1]!;
    expect(JSON.parse(notifyCall[1].body).method).toBe('notifications/initialized');
    expect(notifyCall[1].headers['Mcp-Session-Id']).toBe('test-session-id');

    const toolCall = fetchMock.mock.calls[2]!;
    expect(toolCall[0]).toBe('http://localhost:1080/mockserver/mcp');
    const toolBody = JSON.parse(toolCall[1].body);
    expect(toolBody.method).toBe('tools/call');
    expect(toolBody.params.name).toBe('mock_llm_completion');
    expect(toolBody.params.arguments.provider).toBe('ANTHROPIC');
    expect(toolCall[1].headers['Mcp-Session-Id']).toBe('test-session-id');
  });

  it('reuses cached session across calls to the same baseUrl', async () => {
    const firstTool: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({ jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{"a":1}' }] } }),
    };
    const secondTool: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({ jsonrpc: '2.0', id: 2, result: { content: [{ type: 'text', text: '{"b":2}' }] } }),
    };
    const fetchMock = sequenceFetch([initResponse(), notificationAck(), firstTool, secondTool]);

    await callMcpTool('http://localhost:1080', 'tool_a', {});
    await callMcpTool('http://localhost:1080', 'tool_b', {});

    // 4 calls: initialize + notifications + 2 tool calls (no re-init).
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('reinitializes once if the cached session is rejected', async () => {
    const rejected: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        error: { code: -32600, message: "Missing or invalid Mcp-Session-Id header. Call 'initialize' first." },
      }),
    };
    const successful: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({ jsonrpc: '2.0', id: 2, result: { content: [{ type: 'text', text: '{"ok":true}' }] } }),
    };
    const fetchMock = sequenceFetch([
      initResponse('expired-session'),
      notificationAck(),
      rejected,
      initResponse('fresh-session'),
      notificationAck(),
      successful,
    ]);

    const result = await callMcpTool('http://localhost:1080', 'tool_x', {});

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(6);
  });

  it('returns error on HTTP failure of the tool call', async () => {
    const failedTool: MockResponse = {
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      headers: { get: () => null },
    };
    sequenceFetch([initResponse(), notificationAck(), failedTool]);

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(false);
    expect(result.error).toContain('500');
  });

  it('returns error on JSON-RPC error response that is not a session error', async () => {
    const errResponse: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        error: { code: -32600, message: 'Invalid Request' },
      }),
    };
    sequenceFetch([initResponse(), notificationAck(), errResponse]);

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(false);
    expect(result.error).toEqual({ code: -32600, message: 'Invalid Request' });
  });

  it('handles error in tool result content', async () => {
    const toolResponse: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [
            { type: 'text', text: '{"error":true,"message":"provider is required"}' },
          ],
        },
      }),
    };
    sequenceFetch([initResponse(), notificationAck(), toolResponse]);

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(false);
    expect(result.error).toEqual({ error: true, message: 'provider is required' });
  });

  it('handles non-JSON text in result content', async () => {
    const toolResponse: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [
            { type: 'text', text: 'not json' },
          ],
        },
      }),
    };
    sequenceFetch([initResponse(), notificationAck(), toolResponse]);

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ text: 'not json' });
  });

  it('handles result without content array', async () => {
    const toolResponse: MockResponse = {
      ok: true,
      headers: { get: () => null },
      json: () => Promise.resolve({ jsonrpc: '2.0', id: 1, result: { status: 'created' } }),
    };
    sequenceFetch([initResponse(), notificationAck(), toolResponse]);

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ status: 'created' });
  });
});
