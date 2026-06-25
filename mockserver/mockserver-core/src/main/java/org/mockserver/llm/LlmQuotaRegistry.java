package org.mockserver.llm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-wide, stateful request quota for LLM responses — a fixed-window rate
 * limiter. Unlike the probabilistic 429 in {@link org.mockserver.model.LlmChaosProfile},
 * this is deterministic and stateful: it counts how many requests have hit a named
 * quota within the current time window and reports when the limit is exceeded, so a
 * test can drive an agent into a hard rate-limit (e.g. "the 4th call in 60s gets 429").
 *
 * <p>Quotas are keyed by name, so several expectations that share a {@code quotaName}
 * share one counter (model an upstream account limit), while distinct names are
 * independent. State is held in a {@link ConcurrentHashMap} and each acquire is an
 * atomic per-key update, safe under concurrent requests.
 *
 * <p>The time source is injectable so window behaviour is unit-testable without
 * sleeping; production uses {@link System#currentTimeMillis()}.
 */
public class LlmQuotaRegistry {

    private static final LlmQuotaRegistry INSTANCE = new LlmQuotaRegistry(System::currentTimeMillis);

    // Cap the number of distinct quota names retained. Without this, a workload that
    // uses many one-off quota names would leak a map entry per name forever (a window
    // is only replaced when the same name is reused). When the cap is exceeded we
    // opportunistically evict windows that have already expired relative to their own
    // window length, which is always safe — an expired window is recreated fresh on
    // next access anyway.
    private static final int MAX_WINDOWS = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LlmQuotaRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public static LlmQuotaRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Record one request against the named quota and report whether it is allowed.
     *
     * <p>Fixed-window semantics: the first request in a window starts it; the window
     * expires {@code windowMillis} after it started, after which the next request
     * starts a fresh window. A request is allowed when the in-window count (including
     * itself) is at or below {@code limit}.
     *
     * @return {@code true} if the request is within the quota, {@code false} if it
     * exceeds the limit for the current window.
     */
    public boolean tryAcquire(String name, int limit, long windowMillis) {
        return tryAcquire(name, (long) limit, windowMillis, 1L);
    }

    /**
     * Record {@code amount} units (e.g. tokens) against the named quota and report
     * whether the cumulative total is within the limit.
     *
     * <p>Semantics are the same fixed-window as {@link #tryAcquire(String, int, long)}
     * but the counter increments by {@code amount} instead of 1, and the limit is a
     * {@code long} to support large token-based quotas (TPM/TPD).
     *
     * @param name        shared counter key
     * @param limit       maximum allowed units per window (must be &gt;= 0)
     * @param windowMillis window length in milliseconds (must be &gt; 0)
     * @param amount      units to consume (must be &gt;= 0)
     * @return {@code true} if the cumulative in-window total (including this call)
     * is at or below {@code limit}, {@code false} otherwise.
     */
    public boolean tryAcquire(String name, long limit, long windowMillis, long amount) {
        if (name == null || limit < 0 || windowMillis <= 0 || amount < 0) {
            // misconfigured quota is a no-op (never rate-limits) — fail open
            return true;
        }
        long now = clock.getAsLong();
        Window updated = windows.compute(name, (key, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMillis) {
                return new Window(now, amount, windowMillis);
            }
            return new Window(existing.startMillis, existing.count + amount, existing.windowMillis);
        });
        if (windows.size() > MAX_WINDOWS) {
            evictExpiredWindows(now);
        }
        return updated.count <= limit;
    }

    /**
     * Opportunistically remove windows that have already expired (relative to their
     * own window length). Safe because an expired window is reconstructed fresh on the
     * next {@code tryAcquire} for that name; this only reclaims memory for idle, never
     * reused quota names. Uses {@code remove(key, value)} so a window that was
     * concurrently refreshed is not dropped.
     */
    private void evictExpiredWindows(long now) {
        for (java.util.Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            if (now - window.startMillis >= window.windowMillis) {
                windows.remove(entry.getKey(), window);
            }
        }
    }

    /**
     * Clear all quota state. Called on server reset and for test isolation.
     */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private final long startMillis;
        private final long count; // long (not int) so a never-expiring window can't overflow; compared against int limit by widening
        private final long windowMillis; // retained so expired windows can be opportunistically evicted

        private Window(long startMillis, long count, long windowMillis) {
            this.startMillis = startMillis;
            this.count = count;
            this.windowMillis = windowMillis;
        }
    }
}
