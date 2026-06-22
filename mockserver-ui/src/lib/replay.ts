/**
 * Client for MockServer's request-replay endpoint (`PUT /mockserver/replay`).
 * Replays a recorded request against its original upstream target and returns
 * the upstream response. Mirrors the inline fetch previously embedded in
 * TrafficInspector's ReplayDialog so the dialog can switch to this client.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * The parsed upstream response. The server returns the upstream response as a
 * JSON document; when the body is not valid JSON it is wrapped as `{ body }` so
 * callers always receive an object they can render.
 */
export type ReplayResult = Record<string, unknown>;

/** Error thrown when the server rejects a replay (non-2xx). */
export class ReplayError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: string,
  ) {
    super(`Replay failed (${status}): ${body}`);
    this.name = 'ReplayError';
  }
}

/**
 * Replay a recorded request against its original upstream target.
 *
 * @param params   Connection parameters identifying the target MockServer.
 * @param httpRequest  The recorded `httpRequest` object to replay.
 * @returns the upstream response parsed as JSON, or `{ body: <text> }` when the
 *          response body is not valid JSON.
 * @throws {ReplayError} when the server responds with a non-2xx status.
 */
export async function replayRequests(
  params: ConnectionParams,
  httpRequest: Record<string, unknown>,
): Promise<ReplayResult> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/replay`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(httpRequest),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new ReplayError(res.status, text);
  }
  try {
    return JSON.parse(text) as ReplayResult;
  } catch {
    return { body: text };
  }
}
