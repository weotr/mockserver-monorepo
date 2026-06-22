import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchGrpcHealth, setGrpcHealth, resetGrpcHealth } from '../lib/grpcHealth';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('grpcHealth client', () => {
  it('fetches the current health map', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ 'svc.A': 'SERVING' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const result = await fetchGrpcHealth(params);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/grpc/health');
    expect(result).toEqual({ 'svc.A': 'SERVING' });
  });

  it('returns an empty map (without throwing) on a non-ok fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    expect(await fetchGrpcHealth(params)).toEqual({});
  });

  it('PUTs the service + status when setting health', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    await setGrpcHealth(params, 'svc.A', 'NOT_SERVING');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/grpc/health');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ service: 'svc.A', status: 'NOT_SERVING' });
  });

  it('throws in the standard "MockServer returned <status>: <body>" shape when setting fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, text: async () => 'unknown service' }));
    await expect(setGrpcHealth(params, 'svc.A', 'SERVING')).rejects.toThrow(
      'MockServer returned 400: unknown service',
    );
  });

  it('throws the standard shape with an empty body when resetting fails with no body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, text: async () => '' }));
    await expect(resetGrpcHealth(params, 'svc.A')).rejects.toThrow('MockServer returned 500: ');
  });
});
