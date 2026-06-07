import { describe, it, expect, vi, afterEach } from 'vitest';
import { getClock, freezeClock, advanceClock, resetClock } from '../lib/clock';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('clock client', () => {
  it('parses GET /clock status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ currentInstant: '2024-01-01T00:00:00Z', currentEpochMillis: 1704067200000, frozen: true }),
    }));
    const status = await getClock(params);
    expect(status).toEqual({ currentInstant: '2024-01-01T00:00:00Z', currentEpochMillis: 1704067200000, frozen: true });
  });

  it('PUTs the freeze / advance / reset actions', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);

    await freezeClock(params);
    await freezeClock(params, '2024-06-01T12:00:00Z');
    await advanceClock(params, 60000);
    await resetClock(params);

    const bodies = fetchMock.mock.calls.map((c) => JSON.parse((c[1] as RequestInit).body as string));
    expect(bodies[0]).toEqual({ action: 'freeze' });
    expect(bodies[1]).toEqual({ action: 'freeze', instant: '2024-06-01T12:00:00Z' });
    expect(bodies[2]).toEqual({ action: 'advance', durationMillis: 60000 });
    expect(bodies[3]).toEqual({ action: 'reset' });
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/clock');
  });

  it('throws the server error message on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 400, statusText: 'Bad Request', json: async () => ({ error: "'durationMillis' must be a positive number" }),
    }));
    await expect(advanceClock(params, 0)).rejects.toThrow('positive number');
  });
});
