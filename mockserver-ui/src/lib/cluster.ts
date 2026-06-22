/**
 * Client for MockServer's cluster status endpoint (`GET /mockserver/cluster`).
 * Reports whether the connected node participates in a clustered state backend,
 * its own node id, the coordinator id, and the list of members. On a single,
 * non-clustered server the endpoint still returns a sensible payload
 * (`clustered: false`, a single local member). See the clustered-state docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** A single cluster member. */
export interface ClusterMember {
  id: string;
  coordinator: boolean;
  local: boolean;
}

/** The cluster status payload. */
export interface ClusterInfo {
  clustered: boolean;
  nodeId: string;
  coordinator: string;
  /** Present only for a named (clustered) backend. */
  clusterName?: string;
  memberCount: number;
  members: ClusterMember[];
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/** Fetch the current cluster status from the server. */
export async function fetchClusterInfo(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<ClusterInfo> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/cluster`, { signal });
  await ensureOk(res);
  return res.json() as Promise<ClusterInfo>;
}
