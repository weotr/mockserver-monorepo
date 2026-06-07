/**
 * Client for MockServer's drift detection endpoint (`/mockserver/drift`).
 * Polls drift records comparing proxied responses against stubs and provides
 * a clear operation. See the Drift Detection docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface DriftRecord {
  expectationId: string;
  driftType: string;
  field: string;
  expectedValue?: string;
  actualValue?: string;
  confidence: number;
  epochTimeMs: number;
  semanticSeverity?: 'BREAKING' | 'WARNING' | 'INFORMATIONAL';
  semanticExplanation?: string;
}

export interface DriftResponse {
  count: number;
  drifts: DriftRecord[];
}

/** Fetch drift records, optionally filtered by expectation id. */
export async function fetchDriftRecords(
  params: ConnectionParams,
  expectationId?: string,
  limit = 50,
  signal?: AbortSignal,
): Promise<DriftResponse> {
  const base = buildBaseUrl(params);
  const qs = new URLSearchParams();
  if (expectationId) qs.set('expectationId', expectationId);
  qs.set('limit', String(limit));
  const res = await fetch(`${base}/mockserver/drift?${qs}`, { signal });
  if (!res.ok) return { count: 0, drifts: [] };
  return res.json();
}

/** Clear all accumulated drift records. */
export async function clearDrift(params: ConnectionParams): Promise<void> {
  const base = buildBaseUrl(params);
  await fetch(`${base}/mockserver/drift/clear`, { method: 'PUT' });
}
