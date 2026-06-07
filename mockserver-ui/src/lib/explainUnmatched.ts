/**
 * Client for MockServer's PUT /mockserver/explainUnmatched — for each recent request that matched
 * no expectation, the server returns the closest expectations ranked by how many match fields they
 * differ on, with per-field differences and remediation hints. Answers "why did nothing match?".
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface ClosestExpectation {
  expectationId?: string;
  expectationPath?: string;
  expectationMethod?: string;
  matches: boolean;
  matchedFieldCount: number;
  totalFieldCount: number;
  differingFieldCount: number;
  differences?: Record<string, string[]>;
  remediation?: Record<string, string>;
}

export interface UnmatchedRequest {
  timestamp?: string;
  method: string;
  path: string;
  totalExpectationsEvaluated: number;
  closestExpectations: ClosestExpectation[];
}

export interface ExplainUnmatchedResult {
  correlationId: string;
  timestamp: string;
  unmatchedRequestCount: number;
  truncated: boolean;
  unmatchedRequests: UnmatchedRequest[];
}

export async function explainUnmatched(
  params: ConnectionParams,
  limit = 10,
  signal?: AbortSignal,
): Promise<ExplainUnmatchedResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/explainUnmatched`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ limit }),
    signal,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `explainUnmatched failed (HTTP ${res.status} ${res.statusText})`);
  }
  const body = (await res.json()) as Partial<ExplainUnmatchedResult>;
  return {
    correlationId: body.correlationId ?? '',
    timestamp: body.timestamp ?? '',
    unmatchedRequestCount: body.unmatchedRequestCount ?? 0,
    truncated: body.truncated ?? false,
    unmatchedRequests: Array.isArray(body.unmatchedRequests) ? body.unmatchedRequests : [],
  };
}
