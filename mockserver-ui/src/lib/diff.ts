/**
 * Client for MockServer's request diff endpoint (`/mockserver/diff`).
 * Compares two captured requests and returns field-by-field diffs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface FieldDiff {
  field: string;
  expectedValue?: string;
  actualValue?: string;
  diffType: 'ADDED' | 'REMOVED' | 'CHANGED';
}

export interface DiffResult {
  diffCount: number;
  identical: boolean;
  diffs: FieldDiff[];
}

/** Diff two captured requests field-by-field. */
export async function diffRequests(
  params: ConnectionParams,
  expected: Record<string, unknown>,
  actual: Record<string, unknown>,
): Promise<DiffResult> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/diff`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expected, actual }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
