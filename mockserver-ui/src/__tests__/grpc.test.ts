import { describe, it, expect, vi, afterEach } from 'vitest';
import { listGrpcServices, fetchGrpcHealth, fetchGrpcStatus } from '../lib/grpc';
import { humanizeError } from '../lib/errorMessage';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('grpc client', () => {
  it('lists services via PUT /grpc/services', async () => {
    const services = [
      {
        name: 'com.example.grpc.GreetingService',
        methods: [
          { name: 'Greeting', inputType: 'HelloRequest', outputType: 'HelloResponse', clientStreaming: false, serverStreaming: false },
        ],
      },
    ];
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => services });
    vi.stubGlobal('fetch', fetchMock);

    const result = await listGrpcServices(params);
    expect(result).toEqual(services);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/grpc/services');
    expect((fetchMock.mock.calls[0]![1] as RequestInit).method).toBe('PUT');
  });

  it('returns an empty array when the services body is not an array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    expect(await listGrpcServices(params)).toEqual([]);
  });

  it('fetches the gRPC health map', async () => {
    const health = { _default: 'SERVING', 'payments.v1.PaymentService': 'NOT_SERVING' };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => health });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchGrpcHealth(params);
    expect(result).toEqual(health);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/grpc/health');
  });

  it('throws a humanizable error on a non-ok services response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => 'not found',
    }));
    await expect(listGrpcServices(params)).rejects.toThrow(/MockServer returned 404/);

    // The thrown shape must be understood by humanizeError.
    try {
      await listGrpcServices(params);
    } catch (e) {
      const human = humanizeError(e);
      expect(human.message).toMatch(/isn’t available|not available/i);
    }
  });

  it('combines services + health, tolerating a failing health endpoint', async () => {
    const services = [{ name: 'svc', methods: [] }];
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/grpc/services')) {
        return Promise.resolve({ ok: true, json: async () => services });
      }
      // health endpoint fails — should be swallowed and yield {}
      return Promise.resolve({ ok: false, status: 500, text: async () => 'boom' });
    });
    vi.stubGlobal('fetch', fetchMock);

    const status = await fetchGrpcStatus(params);
    expect(status.services).toEqual(services);
    expect(status.health).toEqual({});
  });
});
