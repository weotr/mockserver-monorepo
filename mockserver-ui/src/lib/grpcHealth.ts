/**
 * Client for MockServer's gRPC health control endpoint
 * (`/mockserver/grpc/health`). Allows overriding gRPC health-check
 * serving status per service and querying current status.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type ServingStatus = 'SERVING' | 'NOT_SERVING' | 'UNKNOWN' | 'SERVICE_UNKNOWN';

/** Fetch the current gRPC health status for all services. */
export async function fetchGrpcHealth(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<Record<string, ServingStatus>> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/grpc/health`, { signal });
  if (!res.ok) return {};
  return res.json();
}

/** Set (override) the gRPC health status for a service. */
export async function setGrpcHealth(
  params: ConnectionParams,
  service: string,
  status: ServingStatus,
): Promise<void> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/grpc/health`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ service, status }),
  });
  if (!res.ok) throw new Error(await res.text());
}

/** Remove the health status override for a service (reset to default). */
export async function resetGrpcHealth(
  params: ConnectionParams,
  service: string,
): Promise<void> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/grpc/health`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ service, remove: true }),
  });
  if (!res.ok) throw new Error(await res.text());
}
