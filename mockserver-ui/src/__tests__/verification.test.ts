import { describe, it, expect, vi, afterEach } from 'vitest';
import { timesToWire, verifyRequest, verifySequence } from '../lib/verification';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => { vi.restoreAllMocks(); });

describe('timesToWire', () => {
  it('maps the four modes, omitting an open bound (server treats absent as -1 = unbounded)', () => {
    expect(timesToWire({ mode: 'atLeast', count: 2 })).toEqual({ atLeast: 2 });
    expect(timesToWire({ mode: 'atMost', count: 3 })).toEqual({ atMost: 3 });
    expect(timesToWire({ mode: 'exactly', count: 1 })).toEqual({ atLeast: 1, atMost: 1 });
    expect(timesToWire({ mode: 'between', count: 2, atMost: 5 })).toEqual({ atLeast: 2, atMost: 5 });
  });

  it('clamps a between upper bound below the lower bound up to the lower bound', () => {
    expect(timesToWire({ mode: 'between', count: 4, atMost: 1 })).toEqual({ atLeast: 4, atMost: 4 });
  });
});

describe('verifyRequest / verifySequence', () => {
  it('treats 202 as verified and posts the right body to /verify', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    const res = await verifyRequest(params, { method: 'POST', path: '/o' }, { mode: 'exactly', count: 2 });
    expect(res).toEqual({ verified: true, failureMessage: null });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verify');
    expect(JSON.parse(init.body)).toEqual({ httpRequest: { method: 'POST', path: '/o' }, times: { atLeast: 2, atMost: 2 } });
  });

  it('propagates a network/fetch rejection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    await expect(verifyRequest(params, { path: '/o' }, { mode: 'atLeast', count: 1 })).rejects.toThrow('network down');
  });

  it('treats 406 as failed and returns the plain-text report', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 406, statusText: 'Not Acceptable', text: async () => 'Request not found exactly 2 times, expected:... but was:...' });
    vi.stubGlobal('fetch', fetchMock);

    const res = await verifyRequest(params, { path: '/o' }, { mode: 'exactly', count: 2 });
    expect(res.verified).toBe(false);
    expect(res.failureMessage).toContain('Request not found');
  });

  it('posts httpRequests[] to /verifySequence', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(params, [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }]);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verifySequence');
    expect(JSON.parse(init.body)).toEqual({ httpRequests: [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }] });
  });
});
