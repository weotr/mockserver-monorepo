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
  // Wave 2 KPIs. May be omitted by the server's NON_EMPTY mapper when zero.
  cacheHitRatio: number;
  oneShotRate: number;
  retryCallCount: number;
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
  // Wave 2 (UNUSED_TOOL_SCHEMA). Tool/function names DEFINED in the request and
  // the estimated token cost of the whole tools schema block.
  definedToolNames?: string[];
  definedToolTokens?: number | null;
}

/**
 * Wave 1 structured remediation for a signal. All strings; nulls allowed.
 * When present it supersedes the back-compat `recommendation` text.
 */
export interface OptimisationFix {
  /** Imperative ≤6 words, e.g. "Enable prompt caching". */
  summary: string;
  /** What to do (1–2 sentences). */
  action: string;
  /** Copy-paste env/config/JSON, or null. */
  configSnippet?: string | null;
  /** Example MockServer expectation JSON, or null. */
  exampleExpectation?: string | null;
  /** Absolute https URL into the consumer docs, or null. */
  docsUrl?: string | null;
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
  // Wave 1. `urgency` ranks within the list (server already sorts by it); `fix`
  // is the structured remediation. Both may be omitted by the server's mapper.
  urgency?: number;
  fix?: OptimisationFix | null;
}

/**
 * Wave 1 in-product verdict — an A–F grade and a "$X recoverable" headline,
 * always present (even for an empty report → grade `A`, zeros).
 */
export interface OptimisationVerdict {
  grade: string;
  rationale: string;
  totalEstimatedSavingUsd: number;
  totalWastedInputTokens: number;
  savingFractionOfSpend: number;
  costIsEstimated: boolean;
  highCount: number;
  mediumCount: number;
  lowCount: number;
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
  // Wave 1. Always present on the wire, but defaulted here for robustness.
  verdict?: OptimisationVerdict;
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
/** Verdict shown when the server omits one (empty report) — grade A, all zeros. */
const DEFAULT_VERDICT: OptimisationVerdict = {
  grade: 'A',
  rationale: 'No optimisation opportunities detected.',
  totalEstimatedSavingUsd: 0,
  totalWastedInputTokens: 0,
  savingFractionOfSpend: 0,
  costIsEstimated: false,
  highCount: 0,
  mediumCount: 0,
  lowCount: 0,
};

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
    totals: {
      ...report.totals,
      // These KPI fields may be absent from a response (the JSON mapper can omit a
      // zero/default value, and older servers predate them). Each `??` default
      // MIRRORS the server-side field default so an omitted field reads correctly:
      // 0 for cacheHitRatio/retryCallCount, but 1 for oneShotRate — a no-retry
      // session is all one-shot, so a missing oneShotRate must read as 100%, not 0%.
      cacheHitRatio: report.totals?.cacheHitRatio ?? 0,
      oneShotRate: report.totals?.oneShotRate ?? 1,
      retryCallCount: report.totals?.retryCallCount ?? 0,
    },
    // The verdict is always present on the wire, but guard for an omitted one.
    verdict: report.verdict
      ? {
        grade: report.verdict.grade ?? 'A',
        rationale: report.verdict.rationale ?? DEFAULT_VERDICT.rationale,
        totalEstimatedSavingUsd: report.verdict.totalEstimatedSavingUsd ?? 0,
        totalWastedInputTokens: report.verdict.totalWastedInputTokens ?? 0,
        savingFractionOfSpend: report.verdict.savingFractionOfSpend ?? 0,
        costIsEstimated: report.verdict.costIsEstimated ?? false,
        highCount: report.verdict.highCount ?? 0,
        mediumCount: report.verdict.mediumCount ?? 0,
        lowCount: report.verdict.lowCount ?? 0,
      }
      : { ...DEFAULT_VERDICT },
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
// Verdict colour + client-side "Copy verdict" text
// ---------------------------------------------------------------------------

/** Theme palette key for a grade letter (A/B = success, C = warning, D/F = error). */
export function gradeColor(grade: string): 'success' | 'warning' | 'error' {
  switch ((grade || '').toUpperCase()) {
    case 'A':
    case 'B':
      return 'success';
    case 'C':
      return 'warning';
    default:
      return 'error';
  }
}

/** "$1.42" — verdict headline money, two decimals. */
function formatVerdictMoney(usd: number): string {
  return `$${(Number.isFinite(usd) ? usd : 0).toFixed(2)}`;
}

/**
 * Build a compact plain-text verdict from a loaded report, CLIENT-SIDE (no
 * fetch). Used by the "Copy verdict" button. Shape:
 *
 *   Grade C — Est. $1.42 recoverable (18% of spend)
 *   <rationale>
 *
 *   [HIGH] <title> — <fix.summary or recommendation>
 *   [MEDIUM] ...
 */
export function buildVerdictText(report: OptimisationReport): string {
  const verdict = report.verdict ?? DEFAULT_VERDICT;
  const pct = Math.round((verdict.savingFractionOfSpend ?? 0) * 100);
  const money = formatVerdictMoney(verdict.totalEstimatedSavingUsd);
  const estSuffix = verdict.costIsEstimated ? ' (est.)' : '';
  const lines: string[] = [
    `Grade ${verdict.grade} — Est. ${money}${estSuffix} recoverable (${pct}% of spend)`,
  ];
  if (verdict.rationale) lines.push(verdict.rationale);
  const signals = sortSignalsBySeverity(report.signals ?? []);
  if (signals.length > 0) {
    lines.push('');
    for (const s of signals) {
      const fixText = s.fix?.summary ?? s.recommendation ?? '';
      lines.push(fixText ? `[${s.severity}] ${s.title} — ${fixText}` : `[${s.severity}] ${s.title}`);
    }
  }
  return lines.join('\n');
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
