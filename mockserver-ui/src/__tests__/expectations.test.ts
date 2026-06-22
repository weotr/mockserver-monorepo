import { describe, it, expect, vi, afterEach } from 'vitest';
import { deleteExpectation } from '../lib/expectations';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('deleteExpectation', () => {
  it('issues PUT /mockserver/clear?type=expectations with the ExpectationId body', async () => {
    const fetchMock = vi.fn(
      (url: string, init?: RequestInit): Promise<Response> => {
        void url;
        void init;
        return Promise.resolve({ ok: true, status: 200, statusText: 'OK' } as Response);
      },
    );
    vi.stubGlobal('fetch', fetchMock);

    await deleteExpectation(params, 'abc-123');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/clear?type=expectations');
    expect(init).toMatchObject({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
    });
    expect(JSON.parse(init!.body as string)).toEqual({ id: 'abc-123' });
  });

  it('honours the base path from connection params', async () => {
    const fetchMock = vi.fn(
      (url: string, init?: RequestInit): Promise<Response> => {
        void url;
        void init;
        return Promise.resolve({ ok: true, status: 200, statusText: 'OK' } as Response);
      },
    );
    vi.stubGlobal('fetch', fetchMock);

    await deleteExpectation({ ...params, basePath: '/proxied' }, 'xyz');

    expect(fetchMock.mock.calls[0]![0]).toBe(
      'http://127.0.0.1:1080/proxied/mockserver/clear?type=expectations',
    );
  });

  it('throws with the server error envelope on a non-2xx response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({ error: 'no such expectation' }),
      })),
    );

    await expect(deleteExpectation(params, 'missing')).rejects.toThrow('no such expectation');
  });

  it('throws the status line when the error body is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => {
          throw new Error('not json');
        },
      })),
    );

    await expect(deleteExpectation(params, 'x')).rejects.toThrow('HTTP 500 Internal Server Error');
  });
});
