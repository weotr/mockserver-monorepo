import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchMode, setMode } from '../lib/mockServerMode';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, body: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => body,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('mockServerMode', () => {
  it('fetchMode GETs /mockserver/mode and returns the parsed mode', async () => {
    const calls = stubFetch(200, { mode: 'SPY', proxyUnmatchedRequests: true });
    const result = await fetchMode(params);
    expect(result).toEqual({ mode: 'SPY', proxyUnmatchedRequests: true });
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/mode');
    expect(calls[0]?.init?.method).toBeUndefined(); // GET
  });

  it('setMode PUTs with the mode query parameter', async () => {
    const calls = stubFetch(200, { mode: 'CAPTURE', proxyUnmatchedRequests: true });
    const result = await setMode(params, 'CAPTURE');
    expect(result.mode).toBe('CAPTURE');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/mode?mode=CAPTURE');
    expect(calls[0]?.init?.method).toBe('PUT');
  });

  it('throws on a non-2xx response', async () => {
    stubFetch(400, 'unknown mode');
    await expect(setMode(params, 'SIMULATE')).rejects.toThrow('unknown mode');
  });
});
