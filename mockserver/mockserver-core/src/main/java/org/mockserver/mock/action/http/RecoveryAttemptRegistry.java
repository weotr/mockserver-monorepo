package org.mockserver.mock.action.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Node-local, stateful per-key attempt counter for the {@link org.mockserver.model.RecoverAfter}
 * recovery primitive. When a response's {@code recoverAfter} is configured with an
 * {@code idempotencyHeader}, the failure window is keyed per {@code (expectationId, header-value)}
 * so that each distinct idempotency key gets its own independent {@code 1..K} window, while requests
 * sharing a key share one counter (model a single logical retry sequence).
 *
 * <p>The default (no idempotency header) recovery path does NOT touch this registry — it counts off
 * the expectation's own match count, so it adds zero new state and zero overhead. This registry is
 * only used on the explicit keyed path.
 *
 * <p><strong>Bounded against memory exhaustion.</strong> Idempotency-key values are client-supplied
 * (typically fresh UUIDs), so an unbounded map would grow without limit and exhaust the heap. The
 * registry is therefore a thread-safe bounded registry — a synchronized access-ordered
 * {@link LinkedHashMap} that evicts the least-recently-used key once {@link #MAX_SIZE} (10,000)
 * keys are held, mirroring {@link org.mockserver.mock.dns.DnsIntentRegistry}. A cold evicted key
 * simply restarts its failure window at attempt {@code 1} on its next request, which matches
 * {@link #reset()} semantics. {@link #nextAttempt} remains an atomic per-key increment, safe under
 * concurrent requests.
 *
 * <p>v1 is node-local (clustering is deferred — like {@code ScenarioManager}, the registry can
 * later move behind the {@code StateBackend} SPI). State is cleared on server reset (see
 * {@code HttpState.reset()}).
 */
public class RecoveryAttemptRegistry {

    /**
     * Maximum number of distinct {@code (expectationId, key)} counters held before the
     * least-recently-used one is evicted. Mirrors {@code DnsIntentRegistry}'s 10,000 cap.
     */
    static final int MAX_SIZE = 10_000;

    /**
     * Key separator: a NUL character cannot appear in a UUID and is extremely unlikely in a
     * client-settable expectation id, so it cannot be produced by concatenating two distinct
     * {@code (expectationId, key)} pairs that should not collide.
     */
    private static final char KEY_SEPARATOR = '\0';

    private static final RecoveryAttemptRegistry INSTANCE = new RecoveryAttemptRegistry();

    // access-order LRU: evicts the least-recently-used key when over MAX_SIZE. Guarded by `this`.
    private final LinkedHashMap<String, AtomicInteger> attempts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
            return size() > MAX_SIZE;
        }
    };

    public static RecoveryAttemptRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Record and return the 1-based attempt number for the given {@code (expectationId, key)} pair.
     * The first call for a pair returns {@code 1}, the next {@code 2}, and so on. If the pair was
     * evicted because the registry was over capacity, counting restarts from {@code 1}.
     *
     * @param expectationId the matched expectation's id (namespaces the counter so distinct
     *                      expectations sharing an idempotency-key value do not collide)
     * @param key           the idempotency-key value from the request header
     * @return the 1-based attempt count for this pair
     */
    public int nextAttempt(String expectationId, String key) {
        String compositeKey = expectationId + KEY_SEPARATOR + key;
        // Single synchronized block keeps map structural mutation (LRU reordering, eviction) and
        // the per-key increment atomic together.
        synchronized (this) {
            return attempts.computeIfAbsent(compositeKey, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    /**
     * Clear all attempt state. Called on server reset and for test isolation.
     */
    public synchronized void reset() {
        attempts.clear();
    }

    /**
     * Returns the number of counters currently held (useful for testing the bound).
     */
    synchronized int size() {
        return attempts.size();
    }
}
