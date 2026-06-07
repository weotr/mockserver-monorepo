import { describe, it, expect, vi, afterEach } from 'vitest';
import { explainUnmatched } from '../lib/explainUnmatched';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('explainUnmatched', () => {
  it('PUTs { limit } and parses the ranked result', async () => {
    const payload = {
      correlationId: 'c1', timestamp: 't', unmatchedRequestCount: 1, truncated: false,
      unmatchedRequests: [{
        method: 'POST', path: '/orders', totalExpectationsEvaluated: 2,
        closestExpectations: [
          { expectationId: 'abcdef12', expectationMethod: 'POST', expectationPath: '/order', matches: false,
            matchedFieldCount: 3, totalFieldCount: 4, differingFieldCount: 1,
            differences: { path: ['expected /order but was /orders'] }, remediation: { path: 'adjust the path matcher' } },
        ],
      }],
    };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => payload });
    vi.stubGlobal('fetch', fetchMock);

    const res = await explainUnmatched(params, 5);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/explainUnmatched');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ limit: 5 });
    expect(res.unmatchedRequests[0]!.closestExpectations[0]!.differingFieldCount).toBe(1);
  });

  it('defaults missing fields to a safe empty result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    const res = await explainUnmatched(params);
    expect(res.unmatchedRequests).toEqual([]);
    expect(res.unmatchedRequestCount).toBe(0);
  });

  it('throws on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, statusText: 'err', text: async () => 'boom' }));
    await expect(explainUnmatched(params)).rejects.toThrow('boom');
  });
});
