import { describe, it, expect, vi, afterEach } from 'vitest';
import { createOidcProvider } from '../lib/oidc';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('createOidcProvider', () => {
  it('PUTs the config and returns the created-expectation count', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [{ id: '1' }, { id: '2' }, { id: '3' }] });
    vi.stubGlobal('fetch', fetchMock);

    const count = await createOidcProvider(params, { issuer: 'http://idp', scopes: ['openid', 'email'], issueExpiredToken: true });
    expect(count).toBe(3);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/oidc');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ issuer: 'http://idp', scopes: ['openid', 'email'], issueExpiredToken: true });
  });

  it('throws the server error text on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', text: async () => 'bad issuer' }));
    await expect(createOidcProvider(params, {})).rejects.toThrow('bad issuer');
  });
});
