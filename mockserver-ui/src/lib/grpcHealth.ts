/**
 * Client for MockServer's gRPC health control endpoint
 * (`/mockserver/grpc/health`). Allows overriding gRPC health-check
 * serving status per service and querying current status.
 *
 * Errors are thrown in the `MockServer returned <status>: <body>` shape
 * understood by {@link humanizeError}.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type ServingStatus = 'SERVING' | 'NOT_SERVING' | 'UNKNOWN' | 'SERVICE_UNKNOWN';

/** Throw in the standard `MockServer returned <status>: <body>` shape for a non-2xx response. */
async function throwServerError(res: Response): Promise<never> {
  const body = await res.text().catch(() => '');
  throw new Error(`MockServer returned ${res.status}: ${body}`);
}

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
  if (!res.ok) await throwServerError(res);
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
  if (!res.ok) await throwServerError(res);
}
