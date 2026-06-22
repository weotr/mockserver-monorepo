package org.mockserver.ratelimit;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.RateLimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-wide, stateful, protocol-neutral rate-limit registry backing the
 * declarative {@link RateLimit} expectation clause. Supports two algorithms:
 *
 * <ul>
 *   <li><b>Fixed window</b> — the fixed-window core extracted from
 *       {@link org.mockserver.mock.action.http.HttpQuotaRegistry}: the first
 *       request in a window starts it, the window expires {@code windowMillis}
 *       after it started, and a request is allowed when the in-window count
 *       (including itself) is at or below {@code limit}.</li>
 *   <li><b>Token bucket</b> — a leaky/token bucket of capacity {@code burst}
 *       refilling at {@code refillPerSecond} tokens/second; a request is allowed
 *       when at least one whole token is available (and consumes it).</li>
 * </ul>
 *
 * <p>Counters are keyed by name, so several expectations sharing a
 * {@link RateLimit#getName() name} share one counter (model an upstream account
 * limit), while distinct names are independent. State is held in
 * {@link ConcurrentHashMap}s and each acquire is an atomic per-key update, safe
 * under concurrent requests. Misconfigured limits fail open (never rate-limit).
 *
 * <p>To bound memory, the total number of distinct named counters is capped at
 * {@link ConfigurationProperties#rateLimitMaxNamedQuotas()}; once the cap is
 * reached a request for a <em>new</em> key fails open (is allowed).
 *
 * <p>The time source is injectable so window/bucket behaviour is unit-testable
 * without sleeping; production uses {@link System#currentTimeMillis()}. State is
 * cleared on server reset (see {@code HttpState.reset()}).
 */
public class RateLimitRegistry {

    private static final RateLimitRegistry INSTANCE = new RateLimitRegistry(System::currentTimeMillis);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RateLimitRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public static RateLimitRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * The decision for a single acquire: whether the request is allowed plus the
     * header-facing limit/remaining/reset values.
     */
    public static final class Decision {
        public final boolean allowed;
        public final long limit;            // X-RateLimit-Limit (window limit or bucket burst)
        public final long remaining;        // X-RateLimit-Remaining
        public final long resetEpochSecond; // X-RateLimit-Reset (unix seconds)

        Decision(boolean allowed, long limit, long remaining, long resetEpochSecond) {
            this.allowed = allowed;
            this.limit = limit;
            this.remaining = remaining;
            this.resetEpochSecond = resetEpochSecond;
        }

        static Decision allow() {
            return new Decision(true, 0, 0, 0);
        }
    }

    /**
     * Record one request against the named rate limit and report the decision.
     *
     * @param rl          the declarative rate limit (may be null/misconfigured -> fail open)
     * @param fallbackKey counter key when {@code rl.getName()} is null (the expectation id)
     * @return the decision; {@link Decision#allowed} is {@code true} when within the limit
     * (or the limit is misconfigured/fails open)
     */
    public Decision tryAcquire(RateLimit rl, String fallbackKey) {
        if (rl == null) {
            return Decision.allow();
        }
        String key = rl.getName() != null ? rl.getName() : fallbackKey;
        if (key == null) {
            return Decision.allow();
        }
        long now = clock.getAsLong();
        RateLimit.Algorithm algorithm = rl.effectiveAlgorithm();
        if (algorithm == RateLimit.Algorithm.TOKEN_BUCKET) {
            return tryAcquireTokenBucket(key, rl, now);
        }
        return tryAcquireFixedWindow(key, rl, now);
    }

    private Decision tryAcquireFixedWindow(String key, RateLimit rl, long now) {
        Integer limitBox = rl.getLimit();
        Long windowBox = rl.getWindowMillis();
        if (limitBox == null || windowBox == null || limitBox < 1 || windowBox < 1) {
            // misconfigured fixed window -> fail open
            return Decision.allow();
        }
        final int limit = limitBox;
        final long windowMillis = windowBox;
        if (isOverCap(windows, key)) {
            return Decision.allow();
        }
        Window updated = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(existing.startMillis, existing.count + 1);
        });
        boolean allowed = updated.count <= limit;
        long remaining = Math.max(0L, limit - updated.count);
        long resetEpochSecond = (updated.startMillis + windowMillis) / 1000L;
        return new Decision(allowed, limit, remaining, resetEpochSecond);
    }

    private Decision tryAcquireTokenBucket(String key, RateLimit rl, long now) {
        Long burstBox = rl.getBurst();
        Double refillBox = rl.getRefillPerSecond();
        if (burstBox == null || refillBox == null || burstBox < 1 || refillBox <= 0.0 || Double.isNaN(refillBox)) {
            // misconfigured token bucket -> fail open
            return Decision.allow();
        }
        final long burst = burstBox;
        final double refillPerSecond = refillBox;
        // tokens are tracked in milli-tokens (1 token == 1000 milli-tokens) for integer math
        final long capacityMilli = burst * 1000L;
        if (isOverCap(buckets, key)) {
            return Decision.allow();
        }
        Bucket updated = buckets.compute(key, (k, existing) -> {
            long tokensMilli;
            if (existing == null) {
                tokensMilli = capacityMilli;
            } else {
                long elapsed = Math.max(0L, now - existing.lastRefillMillis);
                long refilled = (long) (elapsed * refillPerSecond); // milli-tokens added (elapsed ms * tokens/s = milli-tokens)
                tokensMilli = Math.min(capacityMilli, existing.tokensMilli + refilled);
            }
            if (tokensMilli >= 1000L) {
                tokensMilli -= 1000L; // consume one whole token
                return new Bucket(tokensMilli, now, true);
            }
            return new Bucket(tokensMilli, now, false);
        });
        boolean allowed = updated.lastAllowed;
        long remaining = updated.tokensMilli / 1000L;
        // time (seconds) until at least one whole token is available again
        long resetEpochSecond;
        if (remaining >= 1) {
            resetEpochSecond = now / 1000L;
        } else {
            long deficitMilli = 1000L - updated.tokensMilli;
            // milli-tokens / (tokens/s) = milli-seconds; round up to the next whole second
            double millisToOne = deficitMilli / refillPerSecond;
            long secondsToOne = (long) Math.ceil(millisToOne / 1000.0);
            resetEpochSecond = now / 1000L + Math.max(1L, secondsToOne);
        }
        return new Decision(allowed, burst, Math.max(0L, remaining), resetEpochSecond);
    }

    private boolean isOverCap(ConcurrentHashMap<String, ?> map, String key) {
        if (map.containsKey(key)) {
            return false;
        }
        long total = (long) windows.size() + (long) buckets.size();
        return total >= ConfigurationProperties.rateLimitMaxNamedQuotas();
    }

    /**
     * Clear all rate-limit state. Called on server reset and for test isolation.
     */
    public void reset() {
        windows.clear();
        buckets.clear();
    }

    private static final class Window {
        private final long startMillis;
        private final long count; // long so a never-expiring window can't overflow; compared against int limit by widening

        private Window(long startMillis, long count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }

    private static final class Bucket {
        private final long tokensMilli;     // available tokens, in milli-tokens
        private final long lastRefillMillis; // epoch-ms of the last refill calculation
        private final boolean lastAllowed;   // whether the acquire that produced this bucket was allowed

        private Bucket(long tokensMilli, long lastRefillMillis, boolean lastAllowed) {
            this.tokensMilli = tokensMilli;
            this.lastRefillMillis = lastRefillMillis;
            this.lastAllowed = lastAllowed;
        }
    }
}
