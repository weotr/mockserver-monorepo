import { describe, it, expect } from 'vitest';
import {
  normalizeReport,
  sortSignalsBySeverity,
  severityRank,
  gradeColor,
  buildVerdictText,
  type OptimisationReport,
  type OptimisationSignal,
} from '../lib/optimisation';

// A minimal-but-complete report on the wire. Several optional fields are omitted
// on purpose to exercise the NON_EMPTY-mapper defaulting in normalizeReport.
function wireReport(overrides: Partial<OptimisationReport> = {}): OptimisationReport {
  return {
    schemaVersion: 1,
    generatedBy: 'mockserver',
    session: { key: 'all', groupingBasis: 'PROXY_HOST' },
    totals: {
      callCount: 4,
      inputTokens: 1000,
      outputTokens: 200,
      cachedInputTokens: 100,
      reasoningTokens: 0,
      estimatedCostUsd: 0.05,
      costIsEstimated: false,
      totalLatencyMs: 4000,
      toolCallCount: 0,
      // KPI fields intentionally omitted — server omits zero values.
    } as OptimisationReport['totals'],
    redaction: { applied: false },
    ...overrides,
  };
}

describe('normalizeReport', () => {
  it('defaults a missing verdict to grade A with zeros', () => {
    const r = normalizeReport(wireReport());
    expect(r.verdict).toBeDefined();
    expect(r.verdict?.grade).toBe('A');
    expect(r.verdict?.rationale).toBe('No optimisation opportunities detected.');
    expect(r.verdict?.totalEstimatedSavingUsd).toBe(0);
    expect(r.verdict?.savingFractionOfSpend).toBe(0);
    expect(r.verdict?.costIsEstimated).toBe(false);
    expect(r.verdict?.highCount).toBe(0);
    expect(r.verdict?.mediumCount).toBe(0);
    expect(r.verdict?.lowCount).toBe(0);
  });

  it('defaults omitted Totals KPI fields to their server-side defaults', () => {
    // The server's NON_DEFAULT mapper omits a KPI when it equals the server default,
    // so the UI defaults must mirror those: 0 for cacheHitRatio/retryCallCount, but
    // 1 for oneShotRate (a no-retry session is all one-shot, not 0%).
    const r = normalizeReport(wireReport());
    expect(r.totals.cacheHitRatio).toBe(0);
    expect(r.totals.oneShotRate).toBe(1);
    expect(r.totals.retryCallCount).toBe(0);
  });

  it('preserves a server-provided verdict and KPI fields', () => {
    const r = normalizeReport(wireReport({
      verdict: {
        grade: 'C',
        rationale: 'Grade C — 18% recoverable.',
        totalEstimatedSavingUsd: 1.42,
        totalWastedInputTokens: 27000,
        savingFractionOfSpend: 0.18,
        costIsEstimated: true,
        highCount: 1,
        mediumCount: 2,
        lowCount: 0,
      },
      totals: { ...wireReport().totals, cacheHitRatio: 0.62, oneShotRate: 0.83, retryCallCount: 1 },
    }));
    expect(r.verdict?.grade).toBe('C');
    expect(r.verdict?.totalEstimatedSavingUsd).toBe(1.42);
    expect(r.verdict?.costIsEstimated).toBe(true);
    expect(r.totals.cacheHitRatio).toBe(0.62);
    expect(r.totals.oneShotRate).toBe(0.83);
    expect(r.totals.retryCallCount).toBe(1);
  });

  it('still defaults omitted collections', () => {
    const r = normalizeReport(wireReport());
    expect(r.calls).toEqual([]);
    expect(r.signals).toEqual([]);
    expect(r.session.providers).toEqual([]);
    expect(r.session.models).toEqual([]);
  });
});

describe('severity helpers', () => {
  it('ranks HIGH < MEDIUM < LOW and unknown last', () => {
    expect(severityRank('HIGH')).toBeLessThan(severityRank('MEDIUM'));
    expect(severityRank('MEDIUM')).toBeLessThan(severityRank('LOW'));
    expect(severityRank('WAT')).toBe(99);
  });

  it('sorts signals HIGH first, stable within a severity', () => {
    const signals: OptimisationSignal[] = [
      { id: 'a', severity: 'LOW', title: 'a', detail: '', affectedCalls: [], recommendation: '' },
      { id: 'b', severity: 'HIGH', title: 'b', detail: '', affectedCalls: [], recommendation: '' },
      { id: 'c', severity: 'MEDIUM', title: 'c', detail: '', affectedCalls: [], recommendation: '' },
      { id: 'd', severity: 'HIGH', title: 'd', detail: '', affectedCalls: [], recommendation: '' },
    ];
    expect(sortSignalsBySeverity(signals).map((s) => s.id)).toEqual(['b', 'd', 'c', 'a']);
  });
});

describe('gradeColor', () => {
  it('maps A/B to success, C to warning, D/F to error', () => {
    expect(gradeColor('A')).toBe('success');
    expect(gradeColor('b')).toBe('success');
    expect(gradeColor('C')).toBe('warning');
    expect(gradeColor('D')).toBe('error');
    expect(gradeColor('F')).toBe('error');
    expect(gradeColor('')).toBe('error');
  });
});

describe('buildVerdictText', () => {
  it('builds a compact verdict from grade, headline and ranked fix summaries', () => {
    const report = normalizeReport(wireReport({
      verdict: {
        grade: 'C',
        rationale: 'Grade C — 18% recoverable.',
        totalEstimatedSavingUsd: 1.42,
        totalWastedInputTokens: 27000,
        savingFractionOfSpend: 0.18,
        costIsEstimated: false,
        highCount: 1,
        mediumCount: 1,
        lowCount: 0,
      },
      signals: [
        {
          id: 'low', severity: 'LOW', title: 'Low thing', detail: '', affectedCalls: [],
          recommendation: 'do low thing',
        },
        {
          id: 'high', severity: 'HIGH', title: 'Repeated prompt', detail: '', affectedCalls: [0, 1],
          recommendation: 'fallback', fix: { summary: 'Enable prompt caching', action: 'a' },
        },
      ],
    }));
    const text = buildVerdictText(report);
    const lines = text.split('\n');
    expect(lines[0]).toBe('Grade C — Est. $1.42 recoverable (18% of spend)');
    expect(lines[1]).toBe('Grade C — 18% recoverable.');
    // HIGH ranked before LOW; HIGH uses fix.summary, LOW falls back to recommendation.
    expect(text).toContain('[HIGH] Repeated prompt — Enable prompt caching');
    expect(text).toContain('[LOW] Low thing — do low thing');
    expect(text.indexOf('[HIGH]')).toBeLessThan(text.indexOf('[LOW]'));
  });

  it('marks an estimated headline and omits the findings block when there are none', () => {
    const report = normalizeReport(wireReport({
      verdict: {
        grade: 'B', rationale: '', totalEstimatedSavingUsd: 0.5, totalWastedInputTokens: 0,
        savingFractionOfSpend: 0.05, costIsEstimated: true, highCount: 0, mediumCount: 0, lowCount: 0,
      },
    }));
    const text = buildVerdictText(report);
    expect(text).toBe('Grade B — Est. $0.50 (est.) recoverable (5% of spend)');
  });
});
