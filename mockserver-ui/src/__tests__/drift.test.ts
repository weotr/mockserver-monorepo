import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchDriftRecords, clearDrift, type DriftResponse } from '../lib/drift';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('fetchDriftRecords', () => {
  it('fetches from /mockserver/drift with limit and expectationId in query string', async () => {
    const body: DriftResponse = { count: 1, drifts: [{
      expectationId: 'exp-1',
      driftType: 'STATUS',
      field: 'statusCode',
      expectedValue: '200',
      actualValue: '500',
      confidence: 0.95,
      epochTimeMs: 1717000000000,
    }] };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => body,
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchDriftRecords(params, 'exp-1', 20);

    expect(fetchMock).toHaveBeenCalledOnce();
    const url = fetchMock.mock.calls[0]![0] as string;
    expect(url).toContain('/mockserver/drift?');
    expect(url).toContain('expectationId=exp-1');
    expect(url).toContain('limit=20');
    expect(result.count).toBe(1);
    expect(result.drifts[0]!.driftType).toBe('STATUS');
  });

  it('omits expectationId from query when not provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ count: 0, drifts: [] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await fetchDriftRecords(params);

    const url = fetchMock.mock.calls[0]![0] as string;
    expect(url).not.toContain('expectationId');
    expect(url).toContain('limit=50');
  });

  it('throws on a non-OK status instead of silently reporting no drift', async () => {
    // A 500 must NOT be swallowed as { count: 0 } — that made a server error
    // look identical to "no drift detected". The lib now throws so the caller
    // can surface a real error (and route 404s to the "not available" branch).
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      json: async () => ({}),
    }));

    await expect(fetchDriftRecords(params)).rejects.toThrow(/500/);
  });

  it('surfaces the server error envelope message when present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      json: async () => ({ error: 'bad limit' }),
    }));

    await expect(fetchDriftRecords(params)).rejects.toThrow('bad limit');
  });

  it('passes the AbortSignal to fetch', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ count: 0, drifts: [] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const controller = new AbortController();
    await fetchDriftRecords(params, undefined, 50, controller.signal);

    expect(fetchMock.mock.calls[0]![1]).toEqual({ signal: controller.signal });
  });
});

describe('clearDrift', () => {
  it('sends PUT to /mockserver/drift/clear', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await clearDrift(params);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/drift/clear');
    expect(init.method).toBe('PUT');
  });

  it('throws when the clear fails instead of reporting success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      json: async () => ({}),
    }));

    await expect(clearDrift(params)).rejects.toThrow(/500/);
  });
});
