/**
 * Client for MockServer's HTTP/3 status endpoint (`/mockserver/http3status`).
 * Provides a read-only view of whether the experimental HTTP/3 (QUIC) listener
 * is enabled, which UDP port it is bound to, and how many QUIC connections are
 * currently active.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface Http3Status {
  enabled: boolean;
  port: number;
  activeConnections: number;
}

/** Fetch the current HTTP/3 status from the server. */
export async function fetchHttp3Status(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<Http3Status> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/http3status`, { signal });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<Http3Status>;
}
