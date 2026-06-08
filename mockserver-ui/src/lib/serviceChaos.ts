/**
 * Client for MockServer's service-scoped chaos control-plane endpoint
 * (`/mockserver/serviceChaos`). Register one HTTP chaos profile per upstream
 * host and have it applied to every matched forward to that host, with an
 * optional time-to-live (auto-revert). See the Chaos Testing docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface DelayDTO {
  timeUnit?: string;
  value?: number;
}

/** Mirror of the server's HttpChaosProfileDTO (all fields optional). */
export interface HttpChaosProfileDTO {
  errorStatus?: number;
  retryAfter?: string;
  errorProbability?: number;
  dropConnectionProbability?: number;
  latency?: DelayDTO;
  seed?: number;
  succeedFirst?: number;
  failRequestCount?: number;
  outageAfterMillis?: number;
  outageDurationMillis?: number;
  truncateBodyAtFraction?: number;
  malformedBody?: boolean;
  slowResponseChunkSize?: number;
  slowResponseChunkDelay?: DelayDTO;
  quotaName?: string;
  quotaLimit?: number;
  quotaWindowMillis?: number;
  quotaErrorStatus?: number;
  degradationRampMillis?: number;
  graphqlErrors?: boolean;
  graphqlErrorMessage?: string;
  graphqlErrorCode?: string;
  graphqlNullifyData?: boolean;
}

export interface ServiceChaosResponse {
  services: Record<string, HttpChaosProfileDTO>;
  /** Present only for TTL-bearing registrations: host → remaining ms. */
  ttlRemainingMillis?: Record<string, number>;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/serviceChaos`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  // the server returns {"error": "..."} on a 4xx
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/** Fetch the current service-scoped chaos registrations and TTL countdowns. */
export async function fetchServiceChaos(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<ServiceChaosResponse> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<ServiceChaosResponse>;
  return { services: body.services ?? {}, ttlRemainingMillis: body.ttlRemainingMillis };
}

/** Register (or replace) the chaos profile for a host, optionally with a TTL (ms). */
export async function registerServiceChaos(
  params: ConnectionParams,
  host: string,
  chaos: HttpChaosProfileDTO,
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

/** Remove the chaos profile registered for a single host. */
export async function removeServiceChaos(params: ConnectionParams, host: string): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ host, remove: true }),
  });
  await ensureOk(res);
}

/** Patch (partially update) the chaos profile for a host. */
export async function patchServiceChaos(
  params: ConnectionParams,
  host: string,
  partial: Partial<HttpChaosProfileDTO>,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ host, chaos: partial }),
  });
  await ensureOk(res);
}

/** Clear all service-scoped chaos registrations. */
export async function clearServiceChaos(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clear: true }),
  });
  await ensureOk(res);
}

function pct(value: number): string {
  return `${Math.round(value * 100)}%`;
}

function delayMillis(delay: DelayDTO | undefined): number | undefined {
  if (!delay || delay.value == null) return undefined;
  const unit = (delay.timeUnit ?? 'MILLISECONDS').toUpperCase();
  switch (unit) {
    case 'DAYS':
      return delay.value * 86_400_000;
    case 'HOURS':
      return delay.value * 3_600_000;
    case 'MINUTES':
      return delay.value * 60_000;
    case 'SECONDS':
      return delay.value * 1000;
    case 'MICROSECONDS':
      return delay.value / 1000;
    case 'NANOSECONDS':
      return delay.value / 1_000_000;
    default:
      return delay.value; // MILLISECONDS
  }
}

/**
 * Human-readable summary chips for a chaos profile — one short phrase per
 * populated facet, in a stable order. Empty when nothing is configured.
 */
export function summarizeChaosProfile(profile: HttpChaosProfileDTO): string[] {
  const parts: string[] = [];
  // The server only injects an error when errorStatus is set; errorProbability
  // alone is a no-op (HttpActionHandler.chaosErrorResponseOrNull returns null
  // when errorStatus == null). So the error facet keys on errorStatus, and the
  // probability is shown only as a modifier of that status.
  if (profile.errorStatus != null) {
    const prob = profile.errorProbability != null ? ` @ ${pct(profile.errorProbability)}` : '';
    const retry = profile.retryAfter ? ` retry-after=${profile.retryAfter}` : '';
    parts.push(`error ${profile.errorStatus}${prob}${retry}`);
  }
  if (profile.dropConnectionProbability != null) {
    parts.push(`drop @ ${pct(profile.dropConnectionProbability)}`);
  }
  const latencyMs = delayMillis(profile.latency);
  if (latencyMs != null) {
    parts.push(`+${Math.round(latencyMs)}ms latency`);
  }
  if (profile.truncateBodyAtFraction != null) {
    parts.push(`truncate to ${pct(profile.truncateBodyAtFraction)}`);
  }
  if (profile.malformedBody) {
    parts.push('malformed body');
  }
  if (profile.slowResponseChunkSize != null) {
    parts.push('slow response');
  }
  if (profile.quotaName != null || profile.quotaLimit != null) {
    const limit = profile.quotaLimit != null ? ` ${profile.quotaLimit}` : '';
    const window = profile.quotaWindowMillis != null ? `/${profile.quotaWindowMillis}ms` : '';
    parts.push(`quota${limit}${window}`.trim());
  }
  if (profile.succeedFirst != null || profile.failRequestCount != null) {
    const succeed = profile.succeedFirst != null ? `succeed first ${profile.succeedFirst}` : null;
    const fail = profile.failRequestCount != null ? `fail ${profile.failRequestCount}` : null;
    parts.push([succeed, fail].filter(Boolean).join(', '));
  }
  if (profile.degradationRampMillis != null) {
    parts.push(`ramp over ${profile.degradationRampMillis}ms`);
  }
  if (profile.outageAfterMillis != null || profile.outageDurationMillis != null) {
    parts.push('outage window');
  }
  if (profile.seed != null) {
    parts.push(`seed ${profile.seed}`);
  }
  if (profile.graphqlErrors) {
    const code = profile.graphqlErrorCode ? ` (${profile.graphqlErrorCode})` : '';
    parts.push(`GraphQL error${code}`);
  }
  if (profile.graphqlErrors && profile.graphqlNullifyData) {
    parts.push('nullify data');
  }
  return parts;
}

/** Format a remaining-TTL in ms as a compact countdown (e.g. "1m 05s", "12s"). */
export function formatTtl(remainingMillis: number): string {
  const totalSeconds = Math.max(0, Math.round(remainingMillis / 1000));
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
}
