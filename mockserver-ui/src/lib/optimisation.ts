/**
 * Client for MockServer's LLM optimisation-report endpoint
 * (`GET /mockserver/llm/optimisationReport`).
 *
 * The endpoint turns captured LLM traffic into either a structured JSON bundle
 * (`format=json`, the {@link OptimisationReport}) or a copy-paste Markdown
 * "optimisation brief" (`format=markdown`). The JSON shape mirrors the FROZEN
 * contract `docs/plans/llm-optimisation-export-contract.local.md` §2.
 *
 * Export-only — MockServer never calls an LLM itself. All numbers here are
 * deterministic and produced server-side; the UI trusts them for display.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

// ---------------------------------------------------------------------------
// Contract types (§2)
// ---------------------------------------------------------------------------

export type GroupingBasis = 'ISOLATION_KEY' | 'PROXY_HOST';
export type SignalSeverity = 'HIGH' | 'MEDIUM' | 'LOW';

export interface OptimisationSession {
  key: string;
  groupingBasis: GroupingBasis;
  // May be omitted by the server's JSON mapper when empty (e.g. empty report).
  providers?: string[];
  models?: string[];
}

export interface OptimisationTotals {
  callCount: number;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  reasoningTokens: number;
  estimatedCostUsd: number;
  costIsEstimated: boolean;
  totalLatencyMs: number;
  toolCallCount: number;
}

export interface OptimisationToolCall {
  name: string;
  argsFingerprint?: string | null;
  resultTokens?: number | null;
}

export interface OptimisationCall {
  index: number;
  path: string;
  provider: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  reasoningTokens: number;
  estimatedCostUsd: number;
  costIsEstimated: boolean;
  latencyMs: number;
  finishReason?: string | null;
  messageCount?: number | null;
  systemPromptTokens?: number | null;
  systemPromptFingerprint?: string | null;
  // May be omitted by the server's JSON mapper when empty — treat as optional.
  toolCalls?: OptimisationToolCall[];
}

export interface OptimisationSignal {
  id: string;
  severity: SignalSeverity;
  title: string;
  detail: string;
  affectedCalls: number[];
  estimatedWastedInputTokens?: number | null;
  estimatedSavingUsd?: number | null;
  recommendation: string;
}

export interface OptimisationRedaction {
  applied: boolean;
  // Either list may be omitted by the server's JSON mapper when empty.
  redactedHeaders?: string[];
  redactedBodyFields?: string[];
}

export interface OptimisationReport {
  schemaVersion: number;
  generatedBy: string;
  session: OptimisationSession;
  totals: OptimisationTotals;
  // calls/signals may be omitted by the server's JSON mapper when empty
  // (NON_EMPTY inclusion) — e.g. the empty-report path before any traffic.
  calls?: OptimisationCall[];
  signals?: OptimisationSignal[];
  redaction: OptimisationRedaction;
}

/**
 * Fill in any collections the server's NON_EMPTY JSON mapper omitted when empty,
 * so consumers can rely on arrays always being present. Called by the fetch
 * helper; the component additionally guards at use sites (defence in depth).
 */
export function normalizeReport(report: OptimisationReport): OptimisationReport {
  return {
    ...report,
    calls: report.calls ?? [],
    signals: report.signals ?? [],
    session: {
      ...report.session,
      providers: report.session?.providers ?? [],
      models: report.session?.models ?? [],
    },
    redaction: {
      applied: report.redaction?.applied ?? false,
      redactedHeaders: report.redaction?.redactedHeaders ?? [],
      redactedBodyFields: report.redaction?.redactedBodyFields ?? [],
    },
  };
}

// ---------------------------------------------------------------------------
// Severity ordering
// ---------------------------------------------------------------------------

const SEVERITY_RANK: Record<SignalSeverity, number> = {
  HIGH: 0,
  MEDIUM: 1,
  LOW: 2,
};

/** Severity sort rank; unknown severities sort last but keep their relative order. */
export function severityRank(severity: string): number {
  return SEVERITY_RANK[severity as SignalSeverity] ?? 99;
}

/**
 * Signals sorted HIGH → MEDIUM → LOW. Stable within a severity (preserves the
 * server-provided order, which is already saving-ranked per the contract).
 */
export function sortSignalsBySeverity(signals: OptimisationSignal[]): OptimisationSignal[] {
  return [...signals].map((s, i) => ({ s, i }))
    .sort((a, b) => severityRank(a.s.severity) - severityRank(b.s.severity) || a.i - b.i)
    .map(({ s }) => s);
}

// ---------------------------------------------------------------------------
// Fetch helpers
// ---------------------------------------------------------------------------

export interface OptimisationQuery {
  /** Grouping key (session). Omitted → report over ALL captured LLM traffic. */
  session?: string;
  /** Optional upstream-host filter. */
  host?: string;
  /** Optional provider filter. */
  provider?: string;
}

function buildQuery(format: 'json' | 'markdown', query: OptimisationQuery): string {
  const qs = new URLSearchParams();
  qs.set('format', format);
  if (query.session) qs.set('session', query.session);
  if (query.host) qs.set('host', query.host);
  if (query.provider) qs.set('provider', query.provider);
  return qs.toString();
}

/** Throw a `MockServer returned <status>: <body>` error so {@link humanizeError} can map it. */
async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let body = '';
  try {
    body = await res.text();
  } catch {
    // ignore — keep the status line
  }
  throw new Error(`MockServer returned ${res.status}: ${body || res.statusText}`);
}

/** Fetch the structured optimisation report (JSON bundle). */
export async function fetchOptimisationReport(
  params: ConnectionParams,
  query: OptimisationQuery = {},
  signal?: AbortSignal,
): Promise<OptimisationReport> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/llm/optimisationReport?${buildQuery('json', query)}`, { signal });
  await ensureOk(res);
  return normalizeReport(await res.json() as OptimisationReport);
}

/** Fetch the Markdown optimisation brief (for copy / download). */
export async function fetchOptimisationBrief(
  params: ConnectionParams,
  query: OptimisationQuery = {},
  signal?: AbortSignal,
): Promise<string> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/llm/optimisationReport?${buildQuery('markdown', query)}`, { signal });
  await ensureOk(res);
  return res.text();
}
