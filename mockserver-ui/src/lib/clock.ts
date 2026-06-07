/**
 * Client for MockServer's clock control plane (GET/PUT /mockserver/clock). Freezing or advancing
 * the server clock drives TTL expiry and scenario timers deterministically in tests.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface ClockStatus {
  currentInstant: string;
  currentEpochMillis: number;
  frozen: boolean;
}

async function ensureOkJson(res: Response): Promise<Record<string, unknown>> {
  const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) {
    const message = typeof body.error === 'string' ? body.error : `HTTP ${res.status} ${res.statusText}`;
    throw new Error(message);
  }
  return body;
}

export async function getClock(params: ConnectionParams, signal?: AbortSignal): Promise<ClockStatus> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/clock`, { signal });
  const body = await ensureOkJson(res);
  return {
    currentInstant: typeof body.currentInstant === 'string' ? body.currentInstant : '',
    currentEpochMillis: typeof body.currentEpochMillis === 'number' ? body.currentEpochMillis : 0,
    frozen: body.frozen === true,
  };
}

async function putClock(params: ConnectionParams, payload: Record<string, unknown>): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/clock`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  await ensureOkJson(res);
}

/** Freeze the clock — at `instant` (ISO-8601) if given, otherwise at the current time. */
export function freezeClock(params: ConnectionParams, instant?: string): Promise<void> {
  return putClock(params, instant ? { action: 'freeze', instant } : { action: 'freeze' });
}

/** Advance a frozen clock by a positive number of milliseconds. */
export function advanceClock(params: ConnectionParams, durationMillis: number): Promise<void> {
  return putClock(params, { action: 'advance', durationMillis });
}

/** Reset the clock back to following the real system time. */
export function resetClock(params: ConnectionParams): Promise<void> {
  return putClock(params, { action: 'reset' });
}
