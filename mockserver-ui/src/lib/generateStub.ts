/**
 * Client for MockServer's expectation generation endpoint
 * (`/mockserver/generateExpectation`). Given an unmatched request, asks the
 * server to suggest an expectation JSON that would match it.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface GenerateResult {
  suggestions: Record<string, unknown>[];
  confidence: number;
}

/**
 * Generate a stub expectation for the given request.
 *
 * @param params     MockServer connection parameters.
 * @param request    The unmatched HTTP request object (as captured).
 * @param preview    When true, the expectation is not registered automatically.
 */
export async function generateExpectation(
  params: ConnectionParams,
  request: Record<string, unknown>,
  preview = true,
): Promise<GenerateResult> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/generateExpectation`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ request, preview }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

/**
 * Register an expectation on the server.
 *
 * @param params      MockServer connection parameters.
 * @param expectation The full expectation JSON object.
 */
export async function registerExpectation(
  params: ConnectionParams,
  expectation: Record<string, unknown>,
): Promise<void> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/expectation`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(expectation),
  });
  if (!res.ok) throw new Error(await res.text());
}
