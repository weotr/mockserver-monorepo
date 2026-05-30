import type { PrometheusSample } from './prometheusParser';

interface Bucket {
  le: number;
  cumulativeCount: number;
}

/**
 * Estimate a quantile (0..1) from a Prometheus classic histogram's cumulative
 * buckets, using the same linear-interpolation approach as PromQL
 * `histogram_quantile`. Cumulative since the server started (the histogram is
 * monotonic) — not windowed.
 *
 * `base` is the metric base name (e.g. `mock_server_request_duration_seconds`);
 * it reads the `${base}_bucket{le="…"}` samples. Returns `null` when there are
 * no buckets or no observations.
 */
export function histogramQuantile(samples: PrometheusSample[], base: string, q: number): number | null {
  const bucketName = `${base}_bucket`;
  const buckets: Bucket[] = samples
    .filter((s) => s.name === bucketName && s.labels.le !== undefined)
    .map((s) => ({
      le: s.labels.le === '+Inf' ? Number.POSITIVE_INFINITY : Number(s.labels.le),
      cumulativeCount: s.value,
    }))
    .filter((b) => !Number.isNaN(b.le))
    .sort((a, b) => a.le - b.le);

  if (buckets.length === 0) {
    return null;
  }
  const total = buckets[buckets.length - 1]?.cumulativeCount ?? 0; // +Inf bucket = total count
  if (total <= 0) {
    return null;
  }

  const rank = q * total;
  let lowerLe = 0;
  let lowerCount = 0;
  for (const bucket of buckets) {
    if (bucket.cumulativeCount >= rank) {
      if (!Number.isFinite(bucket.le)) {
        // quantile falls in the open-ended +Inf bucket — best estimate is the
        // largest finite upper bound seen so far.
        return lowerLe;
      }
      const bucketCount = bucket.cumulativeCount - lowerCount;
      if (bucketCount <= 0) {
        return bucket.le;
      }
      const fraction = (rank - lowerCount) / bucketCount;
      return lowerLe + fraction * (bucket.le - lowerLe);
    }
    if (Number.isFinite(bucket.le)) {
      lowerLe = bucket.le;
    }
    lowerCount = bucket.cumulativeCount;
  }
  return lowerLe;
}
