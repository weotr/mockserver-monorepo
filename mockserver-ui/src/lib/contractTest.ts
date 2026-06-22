/**
 * Client for MockServer's OpenAPI contract-test endpoint
 * (`PUT /mockserver/contractTest`). Runs the operations of an OpenAPI spec
 * against a live service (the `baseUrl`) and returns a per-operation pass/fail
 * report with validation errors. See the OpenAPI / contract-testing docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** A single operation's contract-test outcome. */
export interface ContractTestOperationResult {
  operationId: string;
  method: string;
  path: string;
  statusCodeReceived: number;
  passed: boolean;
  validationErrors: string[];
}

/** The full contract-test report returned by the server. */
export interface ContractTestReport {
  baseUrl: string;
  totalOperations: number;
  passed: number;
  failed: number;
  allPassed: boolean;
  results: ContractTestOperationResult[];
}

/** Request payload for a contract-test run. */
export interface ContractTestRequest {
  /** A URL, file path, or inline OpenAPI spec (JSON or YAML). */
  spec: string;
  /** The base URL of the service under test, e.g. http://localhost:8080. */
  baseUrl: string;
  /** Optional operationId to restrict the run to a single operation. */
  operationId?: string;
}

/**
 * Throw a humanizable error for a non-OK response. The control plane returns
 * a `{ "error": ... }` envelope on failure, so surface that when present and
 * otherwise keep the status-line message (which `humanizeError` maps too).
 */
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

/** Run an OpenAPI contract test against a live service and return the report. */
export async function runContractTest(
  params: ConnectionParams,
  request: ContractTestRequest,
  signal?: AbortSignal,
): Promise<ContractTestReport> {
  const base = buildBaseUrl(params);
  const body: ContractTestRequest = {
    spec: request.spec,
    baseUrl: request.baseUrl,
  };
  if (request.operationId && request.operationId.trim()) {
    body.operationId = request.operationId.trim();
  }
  const res = await fetch(`${base}/mockserver/contractTest`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  await ensureOk(res);
  return res.json() as Promise<ContractTestReport>;
}
