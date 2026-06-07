package org.mockserver.mock.drift;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding window p50/p95 tracker for response times per expectation ID.
 * Uses a fixed-size circular buffer per expectation. Thread-safe via
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}.
 */
public class PercentileTracker {

    private static final PercentileTracker INSTANCE = new PercentileTracker(100);

    private final int windowSize;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

    public PercentileTracker(int windowSize) {
        this.windowSize = windowSize;
    }

    public static PercentileTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Record a response time observation for the given expectation ID.
     */
    public void record(String expectationId, long responseTimeMs) {
        windows.compute(expectationId, (key, existing) -> {
            long[] buf = existing != null ? existing : new long[windowSize];
            int count = counts.merge(key, 1, Integer::sum);
            int pos = (count - 1) % windowSize;
            buf[pos] = responseTimeMs;
            return buf;
        });
    }

    /**
     * @return the p50 (median) response time for the given expectation, or 0 if no data.
     */
    public long p50(String expectationId) {
        return percentile(expectationId, 50);
    }

    /**
     * @return the p95 response time for the given expectation, or 0 if no data.
     */
    public long p95(String expectationId) {
        return percentile(expectationId, 95);
    }

    /**
     * @return the number of observations recorded for the given expectation.
     */
    public int count(String expectationId) {
        Integer c = counts.get(expectationId);
        return c != null ? Math.min(c, windowSize) : 0;
    }

    private long percentile(String expectationId, int pct) {
        long[] buf = windows.get(expectationId);
        if (buf == null) {
            return 0;
        }
        Integer totalCount = counts.get(expectationId);
        if (totalCount == null || totalCount == 0) {
            return 0;
        }
        int filled = Math.min(totalCount, windowSize);
        long[] copy = new long[filled];
        System.arraycopy(buf, 0, copy, 0, filled);
        Arrays.sort(copy);
        int idx = (int) Math.ceil((pct / 100.0) * filled) - 1;
        return copy[Math.max(0, Math.min(idx, filled - 1))];
    }

    /**
     * Clears all tracked data.
     */
    public void clear() {
        windows.clear();
        counts.clear();
    }
}
