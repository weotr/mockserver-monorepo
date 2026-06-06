/**
 * Pure helpers for turning a history of metric snapshots into the time-series
 * the Metrics view renders. MockServer's metrics are instantaneous gauges
 * (monotonic counts), so throughput is derived client-side as the per-second
 * delta between adjacent snapshots.
 */

import type { PrometheusSample } from './prometheusParser';
import { metricValue, metricValueByLabel, metricSum } from './prometheusParser';

export interface MetricsSnapshot {
  /** epoch millis when the snapshot was scraped */
  at: number;
  samples: PrometheusSample[];
}

/** The value of a gauge across every snapshot (one point per snapshot). */
export function gaugeSeries(history: MetricsSnapshot[], name: string): number[] {
  return history.map((snapshot) => metricValue(snapshot.samples, name));
}

/** The summed value of a labeled counter across all label values, per snapshot (e.g. total across all channels). */
export function gaugeSeriesSum(history: MetricsSnapshot[], name: string): number[] {
  return history.map((snapshot) => metricSum(snapshot.samples, name));
}

/** The value of a label-scoped gauge across every snapshot (e.g. area="heap"). */
export function gaugeSeriesByLabel(
  history: MetricsSnapshot[],
  name: string,
  labelKey: string,
  labelValue: string,
): number[] {
  return history.map((snapshot) => metricValueByLabel(snapshot.samples, name, labelKey, labelValue));
}

/**
 * Per-second rate of a monotonically-increasing count across adjacent
 * snapshots. Returns one fewer point than `history`. A negative delta (the
 * server was reset) clamps to 0 rather than producing a misleading spike.
 */
export function ratePerSecond(history: MetricsSnapshot[], name: string): number[] {
  const rates: number[] = [];
  for (let i = 1; i < history.length; i++) {
    const prev = history[i - 1];
    const cur = history[i];
    if (!prev || !cur) continue;
    const deltaValue = metricValue(cur.samples, name) - metricValue(prev.samples, name);
    const deltaSeconds = (cur.at - prev.at) / 1000;
    rates.push(deltaSeconds > 0 ? Math.max(0, deltaValue / deltaSeconds) : 0);
  }
  return rates;
}

/** Most recent per-second rate for `name`, or 0 if not enough history. */
export function latestRate(history: MetricsSnapshot[], name: string): number {
  const rates = ratePerSecond(history, name);
  return rates.length > 0 ? (rates[rates.length - 1] ?? 0) : 0;
}
