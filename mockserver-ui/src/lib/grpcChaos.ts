/**
 * Client for MockServer's gRPC fault-injection chaos control-plane endpoint
 * (`/mockserver/grpcChaos`). Register one gRPC chaos profile per service
 * and have it applied to every matched RPC call to that service, with an
 * optional time-to-live (auto-revert).
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** Mirror of the server's GrpcChaosProfileDTO (all fields optional). */
export interface GrpcChaosProfileDTO {
  errorStatusCode?: string;
  errorMessage?: string;
  errorProbability?: number;
  seed?: number;
  latencyMs?: number;
  succeedFirst?: number;
  failRequestCount?: number;
  quotaName?: string;
  quotaLimit?: number;
  quotaWindowMillis?: number;
}

export interface GrpcChaosResponse {
  services: Record<string, GrpcChaosProfileDTO>;
  /** Present only for TTL-bearing registrations: service -> remaining ms. */
  ttlRemainingMillis?: Record<string, number>;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/grpcChaos`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body -- keep the status-line message
  }
  throw new Error(message);
}

/** Fetch the current gRPC fault-injection chaos registrations and TTL countdowns. */
export async function fetchGrpcChaos(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<GrpcChaosResponse> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<GrpcChaosResponse>;
  return { services: body.services ?? {}, ttlRemainingMillis: body.ttlRemainingMillis };
}

/** Register (or replace) the gRPC chaos profile for a service, optionally with a TTL (ms). */
export async function registerGrpcChaos(
  params: ConnectionParams,
  service: string,
  chaos: GrpcChaosProfileDTO,
  ttlMillis?: number,
): Promise<void> {
  const payload: Record<string, unknown> = { service, chaos };
  if (ttlMillis != null && ttlMillis > 0) payload.ttlMillis = ttlMillis;
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  await ensureOk(res);
}

/** Remove the gRPC chaos profile registered for a single service. */
export async function removeGrpcChaos(params: ConnectionParams, service: string): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ service, remove: true }),
  });
  await ensureOk(res);
}

/** Clear all gRPC fault-injection chaos registrations. */
export async function clearGrpcChaos(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clear: true }),
  });
  await ensureOk(res);
}

/** Patch (partially update) the gRPC chaos profile for a service. */
export async function patchGrpcChaos(
  params: ConnectionParams,
  service: string,
  partial: Partial<GrpcChaosProfileDTO>,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ service, chaos: partial }),
  });
  await ensureOk(res);
}

function pct(value: number): string {
  return `${Math.round(value * 100)}%`;
}

/**
 * Human-readable summary chips for a gRPC chaos profile -- one short phrase per
 * populated facet, in a stable order. Empty when nothing is configured.
 */
export function summarizeGrpcChaosProfile(profile: GrpcChaosProfileDTO): string[] {
  const parts: string[] = [];
  if (profile.errorStatusCode != null) {
    const prob = profile.errorProbability != null ? ` @ ${pct(profile.errorProbability)}` : '';
    parts.push(`${profile.errorStatusCode}${prob}`);
  }
  if (profile.latencyMs != null) {
    parts.push(`+${profile.latencyMs}ms latency`);
  }
  if (profile.quotaName != null || profile.quotaLimit != null) {
    const limit = profile.quotaLimit != null ? ` ${profile.quotaLimit}` : '';
    const window = profile.quotaWindowMillis != null ? `/${Math.round(profile.quotaWindowMillis / 1000)}s` : '';
    parts.push(`quota${limit}${window}`.trim());
  }
  if (profile.succeedFirst != null || profile.failRequestCount != null) {
    const succeed = profile.succeedFirst != null ? `succeed first ${profile.succeedFirst}` : null;
    const fail = profile.failRequestCount != null ? `fail ${profile.failRequestCount}` : null;
    parts.push([succeed, fail].filter(Boolean).join(', '));
  }
  return parts;
}
