import { describe, it, expect, vi, afterEach } from 'vitest';
import { getConfiguration, updateConfiguration } from '../lib/configuration';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('configuration client', () => {
  it('GETs the configuration', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ logLevel: 'INFO', metricsEnabled: false }) });
    vi.stubGlobal('fetch', fetchMock);
    const config = await getConfiguration(params);
    expect(config).toEqual({ logLevel: 'INFO', metricsEnabled: false });
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/configuration');
  });

  it('PUTs a partial configuration change', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);
    await updateConfiguration(params, { logLevel: 'DEBUG' });
    const [, init] = fetchMock.mock.calls[0]!;
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ logLevel: 'DEBUG' });
  });

  it('throws the server error text on a failed update', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', text: async () => 'invalid logLevel' }));
    await expect(updateConfiguration(params, { logLevel: 'NOPE' })).rejects.toThrow('invalid logLevel');
  });
});
