/**
 * Client for MockServer's verification control plane:
 *   PUT /mockserver/verify          — assert a request was received the expected number of times
 *   PUT /mockserver/verifySequence  — assert an ordered sequence of requests was received
 *
 * Both return 202 Accepted when the assertion holds, or 406 Not Acceptable with a plain-text
 * failure report (the closest matches / count) when it does not.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type VerificationTimesMode = 'atLeast' | 'atMost' | 'exactly' | 'between';

export interface VerificationTimesSpec {
  mode: VerificationTimesMode;
  count: number;
  /** Upper bound for the 'between' mode. */
  atMost?: number;
}

/**
 * Translate a UI times spec to the server's VerificationTimes wire shape. The server treats an
 * absent atLeast/atMost as -1 = unbounded (VerificationTimes.matches), so an open bound is omitted
 * rather than sent as a large sentinel.
 */
export function timesToWire(spec: VerificationTimesSpec): { atLeast?: number; atMost?: number } {
  const count = Math.max(0, Math.floor(spec.count));
  switch (spec.mode) {
    case 'atLeast':
      return { atLeast: count };
    case 'atMost':
      return { atMost: count };
    case 'exactly':
      return { atLeast: count, atMost: count };
    case 'between': {
      const upper = Math.max(count, Math.floor(spec.atMost ?? count));
      return { atLeast: count, atMost: upper };
    }
  }
}

export interface VerifyResult {
  verified: boolean;
  /** Server failure report when not verified; null on success. */
  failureMessage: string | null;
}

async function putVerify(
  params: ConnectionParams,
  path: string,
  body: Record<string, unknown>,
): Promise<VerifyResult> {
  const res = await fetch(`${buildBaseUrl(params)}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  // 202 Accepted = verified; 406 Not Acceptable = failed (with a plain-text report body).
  if (res.status === 202) {
    return { verified: true, failureMessage: null };
  }
  const text = await res.text().catch(() => '');
  return { verified: false, failureMessage: text || `Verification failed (HTTP ${res.status} ${res.statusText})` };
}

/** Verify a single request matcher was received the expected number of times. */
export function verifyRequest(
  params: ConnectionParams,
  httpRequest: Record<string, unknown>,
  times: VerificationTimesSpec,
): Promise<VerifyResult> {
  return putVerify(params, '/mockserver/verify', { httpRequest, times: timesToWire(times) });
}

/** Verify an ordered sequence of request matchers was received (in order, allowing gaps). */
export function verifySequence(
  params: ConnectionParams,
  httpRequests: Record<string, unknown>[],
): Promise<VerifyResult> {
  return putVerify(params, '/mockserver/verifySequence', { httpRequests });
}
