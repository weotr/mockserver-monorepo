import { describe, it, expect } from 'vitest';
import { parsePrometheusText } from '../lib/prometheusParser';
import { histogramQuantile } from '../lib/histogramQuantile';

const BASE = 'mock_server_request_duration_seconds';

// cumulative buckets: le0.01→0, le0.05→50, le0.1→90, le0.5→100, +Inf→100 (total 100)
const HIST = parsePrometheusText(
  [
    `${BASE}_bucket{le="0.01"} 0`,
    `${BASE}_bucket{le="0.05"} 50`,
    `${BASE}_bucket{le="0.1"} 90`,
    `${BASE}_bucket{le="0.5"} 100`,
    `${BASE}_bucket{le="+Inf"} 100`,
    `${BASE}_count 100`,
    `${BASE}_sum 12.3`,
    '',
  ].join('\n'),
);

describe('histogramQuantile', () => {
  it('interpolates p50/p90/p95/p99 from cumulative buckets', () => {
    expect(histogramQuantile(HIST, BASE, 0.5)).toBeCloseTo(0.05, 6);
    expect(histogramQuantile(HIST, BASE, 0.9)).toBeCloseTo(0.1, 6);
    expect(histogramQuantile(HIST, BASE, 0.95)).toBeCloseTo(0.3, 6);
    expect(histogramQuantile(HIST, BASE, 0.99)).toBeCloseTo(0.46, 6);
  });

  it('returns null when the histogram is absent or empty', () => {
    expect(histogramQuantile([], BASE, 0.95)).toBeNull();
    const zero = parsePrometheusText(`${BASE}_bucket{le="+Inf"} 0\n${BASE}_count 0\n`);
    expect(histogramQuantile(zero, BASE, 0.95)).toBeNull();
  });

  it('falls back to the largest finite bound when the quantile is in the +Inf bucket', () => {
    const skewed = parsePrometheusText(
      [`${BASE}_bucket{le="0.01"} 10`, `${BASE}_bucket{le="+Inf"} 100`, ''].join('\n'),
    );
    // rank for p50 is 50; only the +Inf bucket reaches it → floor at 0.01
    expect(histogramQuantile(skewed, BASE, 0.5)).toBe(0.01);
  });
});
