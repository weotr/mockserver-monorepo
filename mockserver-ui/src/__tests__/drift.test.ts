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

  it('returns empty response on non-OK status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
    }));

    const result = await fetchDriftRecords(params);
    expect(result).toEqual({ count: 0, drifts: [] });
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
});
