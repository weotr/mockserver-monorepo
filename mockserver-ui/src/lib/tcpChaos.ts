/**
 * Client for MockServer's TCP-layer chaos control-plane endpoint
 * (`/mockserver/tcpChaos`). Register one TCP chaos profile per upstream
 * host and have it applied at the raw byte level before HTTP decoding,
 * with an optional time-to-live (auto-revert).
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** Mirror of the server's TcpChaosProfileDTO (all fields optional). */
export interface TcpChaosProfileDTO {
  latencyMs?: number;
  down?: boolean;
  bandwidthBytesPerSec?: number;
  slowClose?: boolean;
  timeout?: boolean;
  resetPeer?: boolean;
  slicerChunkSize?: number;
  limitDataBytes?: number;
}

export interface TcpChaosResponse {
  hosts: Record<string, TcpChaosProfileDTO>;
  /** Present only for TTL-bearing registrations: host -> remaining ms. */
  ttlRemainingMillis?: Record<string, number>;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/tcpChaos`;
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

/** Fetch the current TCP-layer chaos registrations and TTL countdowns. */
export async function fetchTcpChaos(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<TcpChaosResponse> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<TcpChaosResponse>;
  return { hosts: body.hosts ?? {}, ttlRemainingMillis: body.ttlRemainingMillis };
}

/** Register (or replace) the TCP chaos profile for a host, optionally with a TTL (ms). */
export async function registerTcpChaos(
  params: ConnectionParams,
  host: string,
  chaos: TcpChaosProfileDTO,
  ttlMillis?: number,
): Promise<void> {
  const payload: Record<string, unknown> = { host, chaos };
  if (ttlMillis != null && ttlMillis > 0) payload.ttlMillis = ttlMillis;
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  await ensureOk(res);
}

/** Remove the TCP chaos profile registered for a single host. */
export async function removeTcpChaos(params: ConnectionParams, host: string): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ host, remove: true }),
  });
  await ensureOk(res);
}

/** Clear all TCP-layer chaos registrations. */
export async function clearTcpChaos(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clear: true }),
  });
  await ensureOk(res);
}

/**
 * Human-readable summary chips for a TCP chaos profile -- one short phrase per
 * populated facet, in a stable order. Empty when nothing is configured.
 */
export function summarizeTcpChaosProfile(profile: TcpChaosProfileDTO): string[] {
  const parts: string[] = [];
  if (profile.down) parts.push('down');
  if (profile.resetPeer) parts.push('reset peer');
  if (profile.timeout) parts.push('timeout');
  if (profile.slowClose) parts.push('slow close');
  if (profile.latencyMs != null) parts.push(`+${profile.latencyMs}ms latency`);
  if (profile.bandwidthBytesPerSec != null) parts.push(`${profile.bandwidthBytesPerSec} B/s bandwidth`);
  if (profile.slicerChunkSize != null) parts.push(`slicer ${profile.slicerChunkSize}B`);
  if (profile.limitDataBytes != null) parts.push(`limit ${profile.limitDataBytes}B`);
  return parts;
}
