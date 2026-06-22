import { describe, it, expect, vi, afterEach } from 'vitest';
import { compareBaseline } from '../lib/baseline';
import { humanizeError } from '../lib/errorMessage';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('baseline compare client', () => {
  it('PUTs the baseline to /baseline/compare and returns the report', async () => {
    const report = {
      added: [{ key: 'GET /api/users' }],
      removed: [{ key: 'GET /hello' }],
      changed: [],
      hasDrift: true,
    };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => report });
    vi.stubGlobal('fetch', fetchMock);

    const result = await compareBaseline(params, { baseline: [{ httpRequest: { path: '/hello' } }] });
    expect(result).toEqual(report);

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/baseline/compare');
    expect((init as RequestInit).method).toBe('PUT');
    expect((init as Record<string, unknown>).headers).toMatchObject({ 'Content-Type': 'application/json' });
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body.baseline).toHaveLength(1);
    expect(body.current).toBeUndefined();
  });

  it('includes the current array when provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ added: [], removed: [], changed: [], hasDrift: false }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await compareBaseline(params, { baseline: [{ a: 1 }], current: [{ b: 2 }] });
    const body = JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string);
    expect(body.current).toEqual([{ b: 2 }]);
  });

  it('defaults missing report fields', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    const result = await compareBaseline(params, { baseline: [] });
    expect(result).toEqual({ added: [], removed: [], changed: [], hasDrift: false });
  });

  it('throws a humanizable error on a 400 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => 'baseline compare request body must contain a "baseline" array of expectations',
    }));

    await expect(compareBaseline(params, { baseline: [] })).rejects.toThrow(/MockServer returned 400/);

    try {
      await compareBaseline(params, { baseline: [] });
    } catch (e) {
      const human = humanizeError(e);
      expect(human.message).toMatch(/rejected as invalid/i);
      expect(human.details).toMatch(/baseline/);
    }
  });
});
