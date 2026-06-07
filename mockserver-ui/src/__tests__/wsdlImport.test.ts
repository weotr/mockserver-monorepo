import { describe, it, expect, vi, afterEach } from 'vitest';
import { importWsdl } from '../lib/wsdlImport';

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

describe('importWsdl', () => {
  it('PUTs the WSDL body and returns created expectations', async () => {
    const created = [{ id: 'CalculatorService.Add' }, { id: 'CalculatorService.Subtract' }];
    const calls = stubFetch(201, created);
    const result = await importWsdl(params, '<definitions/>');
    expect(result).toEqual(created);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/wsdl');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.body).toBe('<definitions/>');
  });

  it('returns an empty array when the response is not an array', async () => {
    stubFetch(201, { unexpected: true });
    expect(await importWsdl(params, '<definitions/>')).toEqual([]);
  });

  it('throws the server message on a bad WSDL', async () => {
    stubFetch(400, 'no SOAP operations found in WSDL');
    await expect(importWsdl(params, 'not wsdl')).rejects.toThrow('no SOAP operations found in WSDL');
  });
});
