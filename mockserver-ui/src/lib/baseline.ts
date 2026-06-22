/**
 * Client for MockServer's baseline-compare endpoint:
 *   PUT /mockserver/baseline/compare
 *
 * The request body is a JSON document with a required `baseline` array of
 * expectations and an optional `current` array. When `current` is omitted the
 * server diffs the baseline against the live recorded expectations.
 *
 * The response is a structured drift report. Errors are thrown in the
 * `MockServer returned <status>: <body>` shape understood by
 * {@link humanizeError}.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** A single field-level difference within a changed interaction. */
export interface FieldDiff {
  field: string;
  expectedValue?: string;
  actualValue?: string;
  diffType?: string;
}

/** One interaction in the diff report, keyed by `METHOD path`. */
export interface InteractionDiff {
  key: string;
  requestDiffs?: FieldDiff[];
  responseDiffs?: FieldDiff[];
}

/** The structured baseline-vs-current drift report the server returns. */
export interface BaselineDiffReport {
  /** Interactions present in current but not in the baseline. */
  added: InteractionDiff[];
  /** Interactions present in the baseline but missing from current. */
  removed: InteractionDiff[];
  /** Interactions present in both whose request/response shape changed. */
  changed: InteractionDiff[];
  /** True when any of added/removed/changed is non-empty. */
  hasDrift: boolean;
}

export interface BaselineCompareRequest {
  /** Required baseline expectations (the known-good snapshot). */
  baseline: Record<string, unknown>[];
  /** Optional current expectations; omit to diff against the live server. */
  current?: Record<string, unknown>[];
}

/**
 * Compare a baseline set of expectations against the current ones. Throws in
 * the `MockServer returned <status>: <body>` shape on a non-2xx response.
 */
export async function compareBaseline(
  params: ConnectionParams,
  request: BaselineCompareRequest,
  signal?: AbortSignal,
): Promise<BaselineDiffReport> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/baseline/compare`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    signal,
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`MockServer returned ${res.status}: ${body}`);
  }
  const report = (await res.json()) as Partial<BaselineDiffReport>;
  return {
    added: report.added ?? [],
    removed: report.removed ?? [],
    changed: report.changed ?? [],
    hasDrift: report.hasDrift ?? false,
  };
}
