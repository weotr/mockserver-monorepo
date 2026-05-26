import { describe, it, expect, vi, beforeEach } from 'vitest';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';

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
});

describe('callMcpTool', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('sends correct JSON-RPC envelope and returns success', async () => {
    const mockResponse = {
      ok: true,
      json: vi.fn().mockResolvedValue({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [
            { type: 'text', text: '{"status":"created","count":1}' },
          ],
        },
      }),
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await callMcpTool(
      'http://localhost:1080',
      'mock_llm_completion',
      { provider: 'ANTHROPIC', path: '/v1/messages', text: 'Hello' },
    );

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ status: 'created', count: 1 });

    const fetchCall = (fetch as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(fetchCall[0]).toBe('http://localhost:1080/mockserver/mcp');
    const body = JSON.parse(fetchCall[1].body);
    expect(body.method).toBe('tools/call');
    expect(body.params.name).toBe('mock_llm_completion');
    expect(body.params.arguments.provider).toBe('ANTHROPIC');
  });

  it('returns error on HTTP failure', async () => {
    const mockResponse = {
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(false);
    expect(result.error).toContain('500');
  });

  it('returns error on JSON-RPC error response', async () => {
    const mockResponse = {
      ok: true,
      json: vi.fn().mockResolvedValue({
        jsonrpc: '2.0',
        id: 1,
        error: { code: -32600, message: 'Invalid Request' },
      }),
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(false);
    expect(result.error).toEqual({ code: -32600, message: 'Invalid Request' });
  });

  it('handles error in tool result content', async () => {
    const mockResponse = {
      ok: true,
      json: vi.fn().mockResolvedValue({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [
            { type: 'text', text: '{"error":true,"message":"provider is required"}' },
          ],
        },
      }),
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(false);
    expect(result.error).toEqual({ error: true, message: 'provider is required' });
  });

  it('handles non-JSON text in result content', async () => {
    const mockResponse = {
      ok: true,
      json: vi.fn().mockResolvedValue({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [
            { type: 'text', text: 'not json' },
          ],
        },
      }),
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ text: 'not json' });
  });

  it('handles result without content array', async () => {
    const mockResponse = {
      ok: true,
      json: vi.fn().mockResolvedValue({
        jsonrpc: '2.0',
        id: 1,
        result: { status: 'created' },
      }),
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await callMcpTool('http://localhost:1080', 'mock_llm_completion', {});

    expect(result.ok).toBe(true);
    expect(result.result).toEqual({ status: 'created' });
  });
});
