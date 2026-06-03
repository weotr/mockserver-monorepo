import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  listScenarios,
  getScenarioState,
  setScenarioState,
  triggerScenario,
} from '../lib/scenarios';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('scenarios client', () => {
  it('lists scenarios from GET /mockserver/scenario', async () => {
    const scenarios = [
      { scenarioName: 'checkout', currentState: 'Started' },
      { scenarioName: 'payment', currentState: 'declined' },
    ];
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ scenarios }) });
    vi.stubGlobal('fetch', fetchMock);
    const result = await listScenarios(params);
    expect(result).toEqual(scenarios);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/scenario');
  });

  it('returns an empty array when the body has no scenarios field', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    expect(await listScenarios(params)).toEqual([]);
  });

  it('surfaces a server error message from the list endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 400, statusText: 'Bad Request',
      json: async () => ({ error: 'failed to list scenarios: boom' }),
    }));
    await expect(listScenarios(params)).rejects.toThrow('failed to list scenarios: boom');
  });

  it('gets a single scenario state by name', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ scenarioName: 'checkout', currentState: 'paid' }) });
    vi.stubGlobal('fetch', fetchMock);
    const result = await getScenarioState(params, 'checkout');
    expect(result.currentState).toBe('paid');
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/scenario/checkout');
  });

  it('PUTs only { state } when no timed transition is supplied', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ scenarioName: 'checkout', currentState: 'paid' }) });
    vi.stubGlobal('fetch', fetchMock);
    await setScenarioState(params, 'checkout', 'paid');
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ state: 'paid' });
  });

  it('includes the timed transition when both delay and nextState are supplied', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ scenarioName: 'checkout', currentState: 'paid', nextState: 'shipped', transitionAfterMs: 5000 }) });
    vi.stubGlobal('fetch', fetchMock);
    await setScenarioState(params, 'checkout', 'paid', 5000, 'shipped');
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(JSON.parse(init.body as string)).toEqual({ state: 'paid', transitionAfterMs: 5000, nextState: 'shipped' });
  });

  it('triggers a transition via /scenario/{name}/trigger', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ scenarioName: 'checkout', currentState: 'cancelled' }) });
    vi.stubGlobal('fetch', fetchMock);
    await triggerScenario(params, 'checkout', 'cancelled');
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/scenario/checkout/trigger');
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(JSON.parse(init.body as string)).toEqual({ newState: 'cancelled' });
  });
});
