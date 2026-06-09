/**
 * Regression coverage for gRPC chaos profile building. The server only injects a gRPC fault
 * when errorProbability > 0, so an error-status-only profile (no probability) was a silent
 * no-op; the builder now defaults the probability to 1 (always inject) when a status code is set.
 */
import { describe, it, expect } from 'vitest';
import { buildGrpcChaosProfile, EMPTY_GRPC_CHAOS_FORM } from '../lib/grpcChaosForm';

describe('buildGrpcChaosProfile', () => {
  it('defaults errorProbability to 1 when a status code is set but no probability given', () => {
    const profile = buildGrpcChaosProfile({ ...EMPTY_GRPC_CHAOS_FORM, service: 'svc', errorStatusCode: 'UNAVAILABLE' });
    expect(profile.errorStatusCode).toBe('UNAVAILABLE');
    expect(profile.errorProbability).toBe(1);
  });

  it('honours an explicit probability alongside a status code', () => {
    const profile = buildGrpcChaosProfile({ ...EMPTY_GRPC_CHAOS_FORM, service: 'svc', errorStatusCode: 'INTERNAL', errorProbability: '0.25' });
    expect(profile.errorProbability).toBe(0.25);
  });

  it('does not invent a probability when no status code is set', () => {
    const profile = buildGrpcChaosProfile({ ...EMPTY_GRPC_CHAOS_FORM, service: 'svc', errorStatusCode: '', latencyMs: '200' });
    expect(profile.errorStatusCode).toBeUndefined();
    expect(profile.errorProbability).toBeUndefined();
    expect(profile.latencyMs).toBe(200);
  });
});
