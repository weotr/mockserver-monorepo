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
                return new Window(now, amount);
            }
            return new Window(existing.startMillis, existing.count + amount);
        });
        return updated.count <= limit;
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

        private Window(long startMillis, long count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }
}
