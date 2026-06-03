import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  listServerCassettes,
  registerServerCassette,
  deleteServerCassette,
} from '../lib/cassetteServer';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('cassetteServer client', () => {
  it('lists cassettes and normalises lastUsed epoch millis to an ISO string', async () => {
    const lastUsed = Date.UTC(2026, 0, 2, 3, 4, 5);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ cassettes: [{ path: '/c/a.json', filename: 'a.json', expectationCount: 3, origin: 'loaded', lastUsed }] }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const result = await listServerCassettes(params);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/cassettes');
    expect(result).toEqual([
      { path: '/c/a.json', filename: 'a.json', expectationCount: 3, origin: 'loaded', lastUsed: new Date(lastUsed).toISOString() },
    ]);
  });

  it('coerces an unknown origin to "loaded" and a missing count to -1', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ cassettes: [{ path: '/c/b.json', filename: 'b.json', origin: 'weird', lastUsed: 0 }] }),
    }));
    const [entry] = await listServerCassettes(params);
    expect(entry!.origin).toBe('loaded');
    expect(entry!.expectationCount).toBe(-1);
    expect(entry!.lastUsed).toBe('');
  });

  it('returns an empty array when the body has no cassettes field', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    expect(await listServerCassettes(params)).toEqual([]);
  });

  it('PUTs a cassette registration body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    await registerServerCassette(params, { path: '/c/a.json', expectationCount: 2, origin: 'recorded' });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/cassettes');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ path: '/c/a.json', expectationCount: 2, origin: 'recorded' });
  });

  it('DELETEs a cassette by path query parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    await deleteServerCassette(params, '/c/a b.json');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/cassettes?path=%2Fc%2Fa%20b.json');
    expect((init as RequestInit).method).toBe('DELETE');
  });

  it('throws on a non-ok list response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, statusText: 'Server Error' }));
    await expect(listServerCassettes(params)).rejects.toThrow('HTTP 500');
  });
});
