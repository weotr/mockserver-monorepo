import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  fetchServiceChaos,
  registerServiceChaos,
  removeServiceChaos,
  clearServiceChaos,
  summarizeChaosProfile,
  formatTtl,
} from '../lib/serviceChaos';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, jsonBody: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => jsonBody,
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('summarizeChaosProfile', () => {
  it('summarises each populated facet', () => {
    expect(summarizeChaosProfile({ errorStatus: 503, errorProbability: 0.5 })).toEqual(['error 503 @ 50%']);
    expect(summarizeChaosProfile({ errorStatus: 500 })).toEqual(['error 500']);
    expect(summarizeChaosProfile({ dropConnectionProbability: 0.25 })).toEqual(['drop @ 25%']);
    expect(summarizeChaosProfile({ latency: { timeUnit: 'MILLISECONDS', value: 250 } })).toEqual(['+250ms latency']);
    expect(summarizeChaosProfile({ latency: { timeUnit: 'SECONDS', value: 2 } })).toEqual(['+2000ms latency']);
    expect(summarizeChaosProfile({ latency: { timeUnit: 'HOURS', value: 1 } })).toEqual(['+3600000ms latency']);
    expect(summarizeChaosProfile({ latency: { timeUnit: 'DAYS', value: 1 } })).toEqual(['+86400000ms latency']);
    expect(summarizeChaosProfile({ truncateBodyAtFraction: 0.5 })).toEqual(['truncate to 50%']);
    expect(summarizeChaosProfile({ malformedBody: true })).toEqual(['malformed body']);
    expect(summarizeChaosProfile({ slowResponseChunkSize: 8 })).toEqual(['slow response']);
    expect(summarizeChaosProfile({ quotaName: 'acct', quotaLimit: 100, quotaWindowMillis: 60000 })).toEqual(['quota 100/60000ms']);
    expect(summarizeChaosProfile({ degradationRampMillis: 30000 })).toEqual(['ramp over 30000ms']);
    expect(summarizeChaosProfile({ outageAfterMillis: 5000, outageDurationMillis: 2000 })).toEqual(['outage window']);
    expect(summarizeChaosProfile({ errorStatus: 500, succeedFirst: 3, failRequestCount: 2 })).toEqual(['error 500', 'succeed first 3, fail 2']);
    expect(summarizeChaosProfile({ failRequestCount: 2 })).toEqual(['fail 2']);
  });

  it('does not summarise errorProbability without an errorStatus (server no-op)', () => {
    expect(summarizeChaosProfile({ errorProbability: 0.3 })).toEqual([]);
  });

  it('summarises a multi-facet profile in a stable order', () => {
    expect(
      summarizeChaosProfile({
        errorStatus: 503,
        errorProbability: 1.0,
        dropConnectionProbability: 0.2,
        latency: { timeUnit: 'MILLISECONDS', value: 100 },
      }),
    ).toEqual(['error 503 @ 100%', 'drop @ 20%', '+100ms latency']);
  });

  it('summarises graphql error with code', () => {
    expect(
      summarizeChaosProfile({ graphqlErrors: true, graphqlErrorCode: 'INTERNAL_SERVER_ERROR' }),
    ).toEqual(['GraphQL error (INTERNAL_SERVER_ERROR)']);
  });

  it('shows nullify data chip only when graphqlErrors is also true', () => {
    expect(
      summarizeChaosProfile({ graphqlErrors: true, graphqlNullifyData: true }),
    ).toEqual(['GraphQL error', 'nullify data']);
    // nullifyData alone (without graphqlErrors) should NOT produce a chip
    expect(
      summarizeChaosProfile({ graphqlNullifyData: true }),
    ).toEqual([]);
  });

  it('returns an empty list for an empty profile', () => {
    expect(summarizeChaosProfile({})).toEqual([]);
  });
});

describe('formatTtl', () => {
  it('formats seconds, minutes and hours', () => {
    expect(formatTtl(12_000)).toBe('12s');
    expect(formatTtl(65_000)).toBe('1m 05s');
    expect(formatTtl(3_725_000)).toBe('1h 02m');
    expect(formatTtl(-500)).toBe('0s');
  });
});

describe('service chaos control-plane calls', () => {
  it('fetchServiceChaos returns services and ttl map', async () => {
    stubFetch(200, { services: { 'a.svc': { errorStatus: 503 } }, ttlRemainingMillis: { 'a.svc': 5000 } });
    const result = await fetchServiceChaos(params);
    expect(result.services['a.svc']?.errorStatus).toBe(503);
    expect(result.ttlRemainingMillis?.['a.svc']).toBe(5000);
  });

  it('fetchServiceChaos defaults services to empty object', async () => {
    stubFetch(200, {});
    const result = await fetchServiceChaos(params);
    expect(result.services).toEqual({});
  });

  it('registerServiceChaos PUTs host + chaos, adding ttlMillis only when set', async () => {
    const calls = stubFetch(200, { status: 'registered' });
    await registerServiceChaos(params, 'a.svc', { errorStatus: 503 }, 60000);
    const body = JSON.parse(String(calls[0]?.init?.body));
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/serviceChaos');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(body).toEqual({ host: 'a.svc', chaos: { errorStatus: 503 }, ttlMillis: 60000 });
  });

  it('registerServiceChaos omits ttlMillis when not provided or zero', async () => {
    const calls = stubFetch(200, {});
    await registerServiceChaos(params, 'a.svc', { errorStatus: 503 });
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ host: 'a.svc', chaos: { errorStatus: 503 } });
    await registerServiceChaos(params, 'b.svc', { errorStatus: 500 }, 0);
    expect(JSON.parse(String(calls[1]?.init?.body))).toEqual({ host: 'b.svc', chaos: { errorStatus: 500 } });
  });

  it('removeServiceChaos PUTs host + remove', async () => {
    const calls = stubFetch(200, { status: 'removed' });
    await removeServiceChaos(params, 'a.svc');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ host: 'a.svc', remove: true });
  });

  it('clearServiceChaos PUTs clear:true', async () => {
    const calls = stubFetch(200, { status: 'cleared' });
    await clearServiceChaos(params);
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ clear: true });
  });

  it('surfaces the server error message on a 4xx', async () => {
    stubFetch(400, { error: "'ttlMillis' must be >= 1 when supplied" });
    await expect(registerServiceChaos(params, 'a.svc', { errorStatus: 503 })).rejects.toThrow(
      "'ttlMillis' must be >= 1 when supplied",
    );
  });
});
