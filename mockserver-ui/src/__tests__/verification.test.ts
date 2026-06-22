import { describe, it, expect, vi, afterEach } from 'vitest';
import { timesToWire, verifyRequest, verifySequence, buildVerifyBody, buildVerifySequenceBody } from '../lib/verification';

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

describe('buildVerifyBody', () => {
  it('defaults to httpRequest:{} when request is empty and no response is provided', () => {
    const body = buildVerifyBody({}, { mode: 'atLeast', count: 1 });
    expect(body).toEqual({ httpRequest: {}, times: { atLeast: 1 } });
  });

  it('includes httpRequest when a request matcher is provided', () => {
    const body = buildVerifyBody({ method: 'GET', path: '/api' }, { mode: 'exactly', count: 2 });
    expect(body).toEqual({ httpRequest: { method: 'GET', path: '/api' }, times: { atLeast: 2, atMost: 2 } });
  });

  it('omits httpRequest when request is empty and a response matcher is provided (response-only)', () => {
    const body = buildVerifyBody({}, { mode: 'atLeast', count: 1 }, { statusCode: 200 });
    expect(body).not.toHaveProperty('httpRequest');
    expect(body.httpResponse).toEqual({ statusCode: 200 });
    expect(body.times).toEqual({ atLeast: 1 });
  });

  it('includes both httpRequest and httpResponse when both are provided', () => {
    const body = buildVerifyBody({ path: '/api' }, { mode: 'atLeast', count: 1 }, { statusCode: 200 });
    expect(body.httpRequest).toEqual({ path: '/api' });
    expect(body.httpResponse).toEqual({ statusCode: 200 });
  });

  it('omits httpResponse when response is an empty object', () => {
    const body = buildVerifyBody({ path: '/api' }, { mode: 'atLeast', count: 1 }, {});
    expect(body).not.toHaveProperty('httpResponse');
  });
});

describe('buildVerifySequenceBody', () => {
  it('defaults to httpRequests with empty objects when all requests are empty and no responses are provided', () => {
    const body = buildVerifySequenceBody([{}, {}]);
    expect(body).toEqual({ httpRequests: [{}, {}] });
  });

  it('includes httpRequests when any step has a request', () => {
    const body = buildVerifySequenceBody([{ path: '/a' }, {}]);
    expect(body).toEqual({ httpRequests: [{ path: '/a' }, {}] });
  });

  it('omits httpRequests when all requests are empty and responses are provided (response-only)', () => {
    const body = buildVerifySequenceBody([{}, {}], [{ statusCode: 201 }, { statusCode: 200 }]);
    expect(body).not.toHaveProperty('httpRequests');
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200 }]);
  });

  it('includes both httpRequests and httpResponses when both are present', () => {
    const body = buildVerifySequenceBody(
      [{ path: '/a' }, { path: '/b' }],
      [{ statusCode: 201 }, { statusCode: 200 }],
    );
    expect(body.httpRequests).toEqual([{ path: '/a' }, { path: '/b' }]);
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200 }]);
  });

  it('includes httpRequests when requests are present even with responses', () => {
    const body = buildVerifySequenceBody(
      [{ path: '/a' }, {}],
      [{ statusCode: 201 }, undefined],
    );
    expect(body.httpRequests).toEqual([{ path: '/a' }, {}]);
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, {}]);
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

  it('omits httpRequest from /verify body when request matcher is empty (response-only verify)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(params, {}, { mode: 'atLeast', count: 1 }, { statusCode: 200 });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verify');
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpRequest');
    expect(body.httpResponse).toEqual({ statusCode: 200 });
    expect(body.times).toEqual({ atLeast: 1 });
  });

  it('posts httpRequests[] to /verifySequence', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(params, [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }]);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verifySequence');
    expect(JSON.parse(init.body)).toEqual({ httpRequests: [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }] });
  });

  it('omits httpRequests from /verifySequence body when all request entries are empty (response-only sequence)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{}, {}],
      [{ statusCode: 201 }, { statusCode: 200 }],
    );

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verifySequence');
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpRequests');
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200 }]);
  });

  // --- Response matcher tests ---

  it('includes httpResponse in /verify body when a response matcher is provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(
      params,
      { method: 'GET', path: '/api' },
      { mode: 'atLeast', count: 1 },
      { statusCode: 200, body: '{"ok":true}' },
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body.httpRequest).toEqual({ method: 'GET', path: '/api' });
    expect(body.httpResponse).toEqual({ statusCode: 200, body: '{"ok":true}' });
    expect(body.times).toEqual({ atLeast: 1 });
  });

  it('omits httpResponse from /verify body when no response matcher is provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(params, { path: '/api' }, { mode: 'atLeast', count: 1 });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('omits httpResponse from /verify body when response matcher is an empty object', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(params, { path: '/api' }, { mode: 'atLeast', count: 1 }, {});

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('includes httpResponses in /verifySequence body when response matchers are provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }],
      [{ statusCode: 201 }, { statusCode: 200, body: 'ok' }],
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body.httpRequests).toHaveLength(2);
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200, body: 'ok' }]);
  });

  it('omits httpResponses from /verifySequence body when no response matchers are provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(params, [{ path: '/a' }, { path: '/b' }]);

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponses');
  });

  it('omits httpResponses from /verifySequence body when all response matchers are empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{ path: '/a' }, { path: '/b' }],
      [undefined, undefined],
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponses');
  });

  it('pads sparse httpResponses with empty objects to preserve index alignment', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{ path: '/a' }, { path: '/b' }, { path: '/c' }],
      [undefined, { statusCode: 200 }, undefined],
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body.httpResponses).toEqual([{}, { statusCode: 200 }, {}]);
  });
});
