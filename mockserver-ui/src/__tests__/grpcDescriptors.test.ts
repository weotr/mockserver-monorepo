import { describe, it, expect, vi, afterEach } from 'vitest';
import { uploadDescriptorSet, listGrpcServices, clearGrpcDescriptors } from '../lib/grpcDescriptors';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('grpcDescriptors client', () => {
  it('uploads raw descriptor bytes to /grpc/descriptors', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    await uploadDescriptorSet(params, new Uint8Array([1, 2, 3]).buffer);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/grpc/descriptors');
    expect((init as RequestInit).method).toBe('PUT');
    expect((init as Record<string, unknown>).headers).toMatchObject({ 'Content-Type': 'application/octet-stream' });
  });

  it('lists services via PUT /grpc/services', async () => {
    const services = [{ name: 'greeter.v1.Greeter', methods: [{ name: 'SayHello', inputType: 'HelloRequest', outputType: 'HelloReply', clientStreaming: false, serverStreaming: false }] }];
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => services });
    vi.stubGlobal('fetch', fetchMock);
    const result = await listGrpcServices(params);
    expect(result).toEqual(services);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/grpc/services');
    expect((fetchMock.mock.calls[0]![1] as RequestInit).method).toBe('PUT');
  });

  it('clears descriptors and surfaces a text error', async () => {
    const okMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', okMock);
    await clearGrpcDescriptors(params);
    expect(okMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/grpc/clear');

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', text: async () => 'failed to load gRPC descriptor: bad bytes' }));
    await expect(uploadDescriptorSet(params, new Uint8Array([0]).buffer)).rejects.toThrow('bad bytes');
  });
});
