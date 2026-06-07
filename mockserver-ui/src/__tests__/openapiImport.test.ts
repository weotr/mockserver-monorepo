import { describe, it, expect, vi, afterEach } from 'vitest';
import { importOpenApi } from '../lib/openapiImport';

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

describe('importOpenApi', () => {
  it('PUTs the spec wrapped as an OpenAPIExpectation and returns created expectations', async () => {
    const created = [{ id: 'openapi:foo:listPets' }, { id: 'openapi:foo:createPets' }];
    const calls = stubFetch(201, created);
    const result = await importOpenApi(params, '{"openapi":"3.0.0"}');
    expect(result).toEqual(created);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/openapi');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(String(calls[0]?.init?.body)).toContain('specUrlOrPayload');
  });

  it('returns an empty array when the response is not an array', async () => {
    stubFetch(201, { unexpected: true });
    expect(await importOpenApi(params, 'https://example.com/spec.yaml')).toEqual([]);
  });

  it('throws the server message on an invalid spec', async () => {
    stubFetch(400, 'unable to load API spec');
    await expect(importOpenApi(params, 'not a spec')).rejects.toThrow('unable to load API spec');
  });
});
