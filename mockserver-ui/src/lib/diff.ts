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

/**
 * Pattern produced by the mismatch analyser for a single field difference, e.g.
 * "expected /api/users but was /api/items" or "expected POST but was GET".
 * Capturing groups: 1 = expected value, 2 = actual value.
 */
const EXPECTED_BUT_WAS = /^expected\s+([\s\S]*?)\s+but was\s+([\s\S]*)$/i;

/**
 * Convert a closest-expectation mismatch (`differences` = field -> human-readable
 * reason strings) into the {@link DiffResult} shape that {@link DiffPanel} renders,
 * so the unmatched-request flow can show a side-by-side "what the request contained
 * vs what the matcher expected" visual diff.
 *
 * Each `expected X but was Y` reason becomes a CHANGED row (expected=X, actual=Y).
 * Reasons that don't match that pattern are still surfaced — the raw reason text is
 * placed in the Expected column with a CHANGED type so no information is lost.
 *
 * `differences` may be undefined/empty (e.g. no closest match, or a closest match
 * with no field-level detail); in that case an identical/zero-diff result is returned
 * and the caller can fall back to the text reasons.
 */
export function mismatchDifferencesToDiffResult(
  differences: Record<string, string[]> | undefined,
): DiffResult {
  const diffs: FieldDiff[] = [];
  for (const [field, reasons] of Object.entries(differences ?? {})) {
    for (const reason of reasons) {
      const match = EXPECTED_BUT_WAS.exec(reason.trim());
      if (match) {
        diffs.push({
          field,
          expectedValue: match[1],
          actualValue: match[2],
          diffType: 'CHANGED',
        });
      } else {
        // Unparseable reason — keep the raw text rather than dropping it.
        diffs.push({
          field,
          expectedValue: reason,
          actualValue: undefined,
          diffType: 'CHANGED',
        });
      }
    }
  }
  return {
    diffCount: diffs.length,
    identical: diffs.length === 0,
    diffs,
  };
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
