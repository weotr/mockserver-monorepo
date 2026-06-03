import { describe, it, expect, vi, afterEach } from 'vitest';
import { registerCrudResource } from '../lib/crud';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('registerCrudResource', () => {
  it('PUTs the config and returns the CRUD result', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ basePath: '/api/users', idField: 'id', idStrategy: 'AUTO_INCREMENT', itemCount: 2 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await registerCrudResource(params, {
      basePath: '/api/users',
      idStrategy: 'AUTO_INCREMENT',
      initialData: [{ name: 'Alice' }, { name: 'Bob' }],
    });
    expect(result).toEqual({ basePath: '/api/users', idField: 'id', idStrategy: 'AUTO_INCREMENT', itemCount: 2 });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/crud');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      basePath: '/api/users',
      idStrategy: 'AUTO_INCREMENT',
      initialData: [{ name: 'Alice' }, { name: 'Bob' }],
    });
  });

  it('sends only basePath when no optional fields are provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ basePath: '/api/items', idField: 'id', idStrategy: 'AUTO_INCREMENT', itemCount: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await registerCrudResource(params, { basePath: '/api/items' });
    expect(result.itemCount).toBe(0);

    const body = JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string);
    expect(body).toEqual({ basePath: '/api/items' });
  });

  it('throws the server error text on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 400, statusText: 'Bad Request',
      text: async () => 'basePath is required',
    }));
    await expect(registerCrudResource(params, { basePath: '' })).rejects.toThrow('basePath is required');
  });

  it('throws a generic message when the error body is empty', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 500, statusText: 'Internal Server Error',
      text: async () => '',
    }));
    await expect(registerCrudResource(params, { basePath: '/x' }))
      .rejects.toThrow('Failed to register CRUD resource (HTTP 500 Internal Server Error)');
  });
});
