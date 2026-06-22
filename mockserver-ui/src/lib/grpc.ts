/**
 * Client for MockServer's gRPC management endpoints used by the gRPC Services
 * dashboard view:
 *   PUT /mockserver/grpc/services  — list the services/methods from the loaded descriptors.
 *   GET /mockserver/grpc/health    — per-service gRPC health-check serving status.
 *
 * Errors are thrown in the `MockServer returned <status>: <body>` shape so
 * callers can pass the caught error straight to {@link humanizeError}.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface GrpcMethod {
  name: string;
  inputType: string;
  outputType: string;
  clientStreaming: boolean;
  serverStreaming: boolean;
}

export interface GrpcService {
  name: string;
  methods: GrpcMethod[];
}

/** gRPC health-check serving status, keyed by service name. The "_default"
 * key (or empty service name) is the overall server serving status. */
export type ServingStatus = 'SERVING' | 'NOT_SERVING' | 'UNKNOWN' | 'SERVICE_UNKNOWN';

export type GrpcHealth = Record<string, ServingStatus>;

/** Combined gRPC management snapshot the panel renders. */
export interface GrpcStatus {
  services: GrpcService[];
  health: GrpcHealth;
}

/**
 * Throw in the `MockServer returned <status>: <body>` shape that
 * {@link humanizeError} understands, reading the (best-effort) response body.
 */
async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  const body = await res.text().catch(() => '');
  throw new Error(`MockServer returned ${res.status}: ${body}`);
}

/** List the services + methods loaded from the gRPC descriptors. */
export async function listGrpcServices(params: ConnectionParams, signal?: AbortSignal): Promise<GrpcService[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/grpc/services`, { method: 'PUT', signal });
  await ensureOk(res);
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? (body as GrpcService[]) : [];
}

/** Fetch the current gRPC health serving status for every registered service. */
export async function fetchGrpcHealth(params: ConnectionParams, signal?: AbortSignal): Promise<GrpcHealth> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/grpc/health`, { signal });
  await ensureOk(res);
  const body = await res.json().catch(() => ({}));
  return (body && typeof body === 'object' ? body : {}) as GrpcHealth;
}

/**
 * Fetch the services and health in parallel. Health is best-effort: if the
 * health endpoint fails (e.g. older server) the services still render with an
 * empty health map rather than failing the whole view.
 */
export async function fetchGrpcStatus(params: ConnectionParams, signal?: AbortSignal): Promise<GrpcStatus> {
  const [services, health] = await Promise.all([
    listGrpcServices(params, signal),
    fetchGrpcHealth(params, signal).catch(() => ({} as GrpcHealth)),
  ]);
  return { services, health };
}
