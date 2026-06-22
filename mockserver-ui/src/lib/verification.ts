/**
 * Client for MockServer's verification control plane:
 *   PUT /mockserver/verify          — assert a request was received, and/or a response was
 *                                      returned for a proxied/forwarded request, the expected
 *                                      number of times
 *   PUT /mockserver/verifySequence  — assert an ordered sequence of requests/responses occurred
 *
 * A verification carries an optional request matcher and an optional response matcher; at least
 * one must be present. When both are present they are correlated against the same recorded
 * request-response exchange. Both endpoints return 202 Accepted when the assertion holds, or
 * 406 Not Acceptable with a plain-text failure report (the closest matches / count) otherwise.
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

/**
 * Build the JSON body for PUT /mockserver/verify from the UI's form state.
 * Exported so the verification codegen module can produce the same wire body
 * the panel actually posts.
 */
export function buildVerifyBody(
  httpRequest: Record<string, unknown>,
  times: VerificationTimesSpec,
  httpResponse?: Record<string, unknown>,
): Record<string, unknown> {
  const hasRequest = !!httpRequest && Object.keys(httpRequest).length > 0;
  const hasResponse = !!httpResponse && Object.keys(httpResponse).length > 0;
  const body: Record<string, unknown> = {};
  if (hasRequest) {
    body.httpRequest = httpRequest;
  } else if (!hasResponse) {
    // No matcher entered → default to a request verification that matches any request,
    // so the body is valid (the server's schema requires at least one matcher).
    body.httpRequest = {};
  }
  body.times = timesToWire(times);
  if (hasResponse) {
    body.httpResponse = httpResponse;
  }
  return body;
}

/**
 * Build the JSON body for PUT /mockserver/verifySequence from the UI's form state.
 * Exported so the verification codegen module can produce the same wire body
 * the panel actually posts.
 */
export function buildVerifySequenceBody(
  httpRequests: Record<string, unknown>[],
  httpResponses?: (Record<string, unknown> | undefined)[],
): Record<string, unknown> {
  const hasAnyResponse = !!httpResponses && httpResponses.some((r) => r && Object.keys(r).length > 0);
  const hasAnyRequest = httpRequests.some((r) => r && Object.keys(r).length > 0);
  const body: Record<string, unknown> = {};
  // Include httpRequests when any step has a request, or when there are no responses at all
  // (default to a request sequence so the body is valid). Empty steps become {} = match any.
  if (hasAnyRequest || !hasAnyResponse) {
    body.httpRequests = httpRequests.map((r) => (r && Object.keys(r).length > 0 ? r : {}));
  }
  if (hasAnyResponse) {
    body.httpResponses = httpResponses!.map((r) => (r && Object.keys(r).length > 0 ? r : {}));
  }
  return body;
}

/**
 * Verify a request matcher and/or a response matcher was matched the expected number of times.
 * Empty matchers are omitted from the wire body; at least one of httpRequest / httpResponse must
 * be non-empty (the caller is responsible for enforcing that). When both are present they are
 * correlated against the same recorded request-response exchange.
 */
export function verifyRequest(
  params: ConnectionParams,
  httpRequest: Record<string, unknown>,
  times: VerificationTimesSpec,
  httpResponse?: Record<string, unknown>,
): Promise<VerifyResult> {
  return putVerify(params, '/mockserver/verify', buildVerifyBody(httpRequest, times, httpResponse));
}

/**
 * Verify an ordered sequence of request and/or response matchers occurred (in order, allowing
 * gaps). httpResponses is index-aligned with httpRequests; either list is omitted from the wire
 * body when every entry is empty, so a request-only, response-only, or correlated sequence can all
 * be expressed.
 */
export function verifySequence(
  params: ConnectionParams,
  httpRequests: Record<string, unknown>[],
  httpResponses?: (Record<string, unknown> | undefined)[],
): Promise<VerifyResult> {
  return putVerify(params, '/mockserver/verifySequence', buildVerifySequenceBody(httpRequests, httpResponses));
}
