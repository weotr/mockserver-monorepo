import { describe, it, expect, vi, afterEach } from 'vitest';
import { importExpectationJson, importCollection } from '../lib/importMocks';

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

// ---------------------------------------------------------------------------
// importExpectationJson
// ---------------------------------------------------------------------------

describe('importExpectationJson', () => {
  it('PUTs a JSON array to /mockserver/expectation and returns created expectations', async () => {
    const created = [{ id: 'exp1' }, { id: 'exp2' }];
    const calls = stubFetch(201, created);
    const result = await importExpectationJson(params, JSON.stringify(created));
    expect(result).toEqual(created);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/expectation');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.headers).toEqual({ 'Content-Type': 'application/json' });
  });

  it('wraps a single object into an array before sending', async () => {
    const single = { httpRequest: { path: '/foo' }, httpResponse: { statusCode: 200 } };
    const calls = stubFetch(201, [single]);
    await importExpectationJson(params, JSON.stringify(single));
    const sentBody = JSON.parse(String(calls[0]?.init?.body));
    expect(Array.isArray(sentBody)).toBe(true);
    expect(sentBody).toHaveLength(1);
  });

  it('throws on non-2xx response', async () => {
    stubFetch(400, 'invalid JSON');
    await expect(importExpectationJson(params, '{}')).rejects.toThrow('invalid JSON');
  });

  it('throws on invalid JSON payload before sending', async () => {
    stubFetch(200, []);
    await expect(importExpectationJson(params, 'not json')).rejects.toThrow();
  });

  it('returns an empty array when the response is not an array', async () => {
    stubFetch(201, { unexpected: true });
    expect(await importExpectationJson(params, '[]')).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// importCollection (HAR + Postman via PUT /mockserver/import?format=...)
// ---------------------------------------------------------------------------

describe('importCollection', () => {
  it('PUTs HAR to /mockserver/import?format=har and returns created expectations', async () => {
    const harBody = '{"log":{"entries":[]}}';
    const created = [{ id: 'har1' }];
    const calls = stubFetch(201, created);
    const result = await importCollection(params, harBody, 'har');
    expect(result).toEqual(created);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/import?format=har');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.headers).toEqual({ 'Content-Type': 'application/json' });
    expect(calls[0]?.init?.body).toBe(harBody);
  });

  it('PUTs Postman to /mockserver/import?format=postman', async () => {
    const postmanBody = '{"info":{"name":"x"},"item":[]}';
    const calls = stubFetch(201, [{ id: 'pm1' }]);
    await importCollection(params, postmanBody, 'postman');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/import?format=postman');
    expect(calls[0]?.init?.body).toBe(postmanBody);
  });

  it('throws the server message on a 400 response', async () => {
    stubFetch(400, 'unable to parse HAR');
    await expect(importCollection(params, '{}', 'har')).rejects.toThrow('unable to parse HAR');
  });

  it('returns an empty array when the response is not an array', async () => {
    stubFetch(201, { unexpected: true });
    expect(await importCollection(params, '{}', 'har')).toEqual([]);
  });
});
