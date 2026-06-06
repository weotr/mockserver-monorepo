/**
 * Minimal parser for the Prometheus text exposition format returned by
 * MockServer's `GET /mockserver/metrics` endpoint.
 *
 * MockServer emits simple gauges, e.g.:
 *
 *   # HELP requests_received_count ...
 *   # TYPE requests_received_count gauge
 *   requests_received_count 13.0
 *   response_expectations_matched_count 5.0
 *   mock_server_build_info{version="6.1.0",artifact_id="mockserver-core"} 1.0
 *
 * We only need name + labels + numeric value; HELP/TYPE comment lines and the
 * optional trailing timestamp are ignored. This is deliberately not a full
 * OpenMetrics parser — just enough for the dashboard's Metrics view.
 */

export interface PrometheusSample {
  name: string;
  labels: Record<string, string>;
  value: number;
}

// metric_name{label="value",...} value [timestamp]
// name:   [a-zA-Z_:][a-zA-Z0-9_:]*
const SAMPLE_LINE = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+(.+?)(?:\s+\d+)?$/;
// label="value" (value may contain escaped \" and \\)
const LABEL_PAIR = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"/g;

function parseValue(raw: string): number {
  const v = raw.trim();
  if (v === '+Inf') return Number.POSITIVE_INFINITY;
  if (v === '-Inf') return Number.NEGATIVE_INFINITY;
  if (v === 'NaN') return Number.NaN;
  return Number(v);
}

function unescape(raw: string): string {
  return raw.replace(/\\(["\\n])/g, (_m, ch: string) => (ch === 'n' ? '\n' : ch));
}

function parseLabels(raw: string | undefined): Record<string, string> {
  const labels: Record<string, string> = {};
  if (!raw) return labels;
  let match: RegExpExecArray | null;
  LABEL_PAIR.lastIndex = 0;
  while ((match = LABEL_PAIR.exec(raw)) !== null) {
    const key = match[1];
    const value = match[2];
    if (key !== undefined && value !== undefined) {
      labels[key] = unescape(value);
    }
  }
  return labels;
}

/** Parse a full Prometheus exposition document into samples. */
export function parsePrometheusText(text: string): PrometheusSample[] {
  const samples: PrometheusSample[] = [];
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) continue;
    const match = SAMPLE_LINE.exec(line);
    if (!match) continue;
    const name = match[1];
    if (name === undefined) continue;
    const value = parseValue(match[3] ?? '');
    if (Number.isNaN(value) && (match[3] ?? '').trim() !== 'NaN') continue; // skip unparseable
    samples.push({ name, labels: parseLabels(match[2]), value });
  }
  return samples;
}

/** First sample matching `name` (ignoring labels), or undefined. */
export function findSample(samples: PrometheusSample[], name: string): PrometheusSample | undefined {
  return samples.find((s) => s.name === name);
}

/** Numeric value for `name`, or `fallback` (default 0) when absent. */
export function metricValue(samples: PrometheusSample[], name: string, fallback = 0): number {
  const sample = findSample(samples, name);
  return sample ? sample.value : fallback;
}

/** First sample matching `name` and `labels[labelKey] === labelValue`. */
export function findSampleByLabel(
  samples: PrometheusSample[],
  name: string,
  labelKey: string,
  labelValue: string,
): PrometheusSample | undefined {
  return samples.find((s) => s.name === name && s.labels[labelKey] === labelValue);
}

/** Numeric value for `name` with a specific label, or `fallback` when absent. */
export function metricValueByLabel(
  samples: PrometheusSample[],
  name: string,
  labelKey: string,
  labelValue: string,
  fallback = 0,
): number {
  const sample = findSampleByLabel(samples, name, labelKey, labelValue);
  return sample ? sample.value : fallback;
}

/** True if any sample with `name` is present (for feature-detecting metrics). */
export function hasMetric(samples: PrometheusSample[], name: string): boolean {
  return samples.some((s) => s.name === name);
}

/** Sum of all samples named `name` across every label combination, or 0 when absent. */
export function metricSum(samples: PrometheusSample[], name: string): number {
  return samples.reduce((total, s) => (s.name === name ? total + s.value : total), 0);
}

/**
 * Distinct values of `labelKey` across all samples named `name`, in first-seen
 * order. Lets the UI render one entry per label value present (e.g. every
 * `fault_type` the server actually emits) without hard-coding the set.
 */
export function labelValues(samples: PrometheusSample[], name: string, labelKey: string): string[] {
  const seen = new Set<string>();
  const values: string[] = [];
  for (const s of samples) {
    if (s.name !== name) continue;
    const value = s.labels[labelKey];
    if (value !== undefined && value !== '' && !seen.has(value)) {
      seen.add(value);
      values.push(value);
    }
  }
  return values;
}
