import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchHttp3Status } from '../lib/http3Status';

describe('fetchHttp3Status', () => {
  const originalFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('returns parsed HTTP/3 status when server responds 200', async () => {
    const mockResponse = { enabled: true, port: 8443, activeConnections: 3 };
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockResponse),
    });

    const result = await fetchHttp3Status({ host: 'localhost', port: '1080', secure: false });

    expect(result).toEqual(mockResponse);
    expect(globalThis.fetch).toHaveBeenCalledWith(
      'http://localhost:1080/mockserver/http3status',
      { signal: undefined },
    );
  });

  it('returns disabled status from server', async () => {
    const mockResponse = { enabled: false, port: -1, activeConnections: 0 };
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockResponse),
    });

    const result = await fetchHttp3Status({ host: 'localhost', port: '1080', secure: false });

    expect(result.enabled).toBe(false);
    expect(result.port).toBe(-1);
    expect(result.activeConnections).toBe(0);
  });

  it('throws on non-OK response', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
    });

    await expect(
      fetchHttp3Status({ host: 'localhost', port: '1080', secure: false }),
    ).rejects.toThrow('HTTP 404 Not Found');
  });

  it('passes abort signal to fetch', async () => {
    const controller = new AbortController();
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ enabled: false, port: -1, activeConnections: 0 }),
    });

    await fetchHttp3Status({ host: 'localhost', port: '1080', secure: false }, controller.signal);

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.any(String),
      { signal: controller.signal },
    );
  });
});
