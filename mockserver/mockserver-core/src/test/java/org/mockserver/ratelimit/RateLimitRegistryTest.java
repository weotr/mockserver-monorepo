package org.mockserver.ratelimit;

import org.junit.Test;
import org.mockserver.model.RateLimit;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.RateLimit.rateLimit;

public class RateLimitRegistryTest {

    /** A controllable clock so window/bucket behaviour is deterministic without sleeping. */
    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000L);

        long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static RateLimit fixedWindow(String name, int limit, long windowMillis) {
        return rateLimit().withName(name).withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW).withLimit(limit).withWindowMillis(windowMillis);
    }

    private static RateLimit tokenBucket(String name, long burst, double refillPerSecond) {
        return rateLimit().withName(name).withAlgorithm(RateLimit.Algorithm.TOKEN_BUCKET).withBurst(burst).withRefillPerSecond(refillPerSecond);
    }

    // --- fixed window ---

    @Test
    public void shouldAllowFixedWindowUpToLimitThenReject() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit rl = fixedWindow("acct", 3, 60_000);

        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // 1
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // 2
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // 3
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false));  // 4 -> over
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false));  // 5 -> still over
    }

    @Test
    public void shouldReportLimitRemainingAndReset() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit rl = fixedWindow("acct", 2, 60_000);

        RateLimitRegistry.Decision first = registry.tryAcquire(rl, "k");
        assertThat(first.allowed, is(true));
        assertThat(first.limit, is(2L));
        assertThat(first.remaining, is(1L));
        // window started at now=1000ms, resets at (1000 + 60000)/1000 = 61 seconds
        assertThat(first.resetEpochSecond, is(61L));

        RateLimitRegistry.Decision third = registry.tryAcquire(rl, "k"); // 2 -> remaining 0
        assertThat(third.remaining, is(0L));
        RateLimitRegistry.Decision over = registry.tryAcquire(rl, "k");  // 3 -> over
        assertThat(over.allowed, is(false));
        assertThat(over.remaining, is(0L));
    }

    @Test
    public void shouldResetFixedWindowAfterExpiry() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit rl = fixedWindow("acct", 1, 60_000);

        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // 1
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false));  // 2 -> over

        clock.advance(60_000);                                        // window elapses
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // fresh window
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false));  // over again
    }

    @Test
    public void shouldShareOneFixedWindowCounterAcrossSameName() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit a = fixedWindow("shared", 2, 60_000);
        RateLimit b = fixedWindow("shared", 2, 60_000);

        // different fallback keys, same name -> one shared counter
        assertThat(registry.tryAcquire(a, "expA").allowed, is(true));
        assertThat(registry.tryAcquire(b, "expB").allowed, is(true));
        assertThat(registry.tryAcquire(a, "expA").allowed, is(false));
    }

    @Test
    public void shouldUseFallbackKeyWhenNameIsNull() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit rl = rateLimit().withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW).withLimit(1).withWindowMillis(60_000L);

        // distinct fallback keys are independent counters
        assertThat(registry.tryAcquire(rl, "exp1").allowed, is(true));
        assertThat(registry.tryAcquire(rl, "exp1").allowed, is(false));
        assertThat(registry.tryAcquire(rl, "exp2").allowed, is(true));
    }

    @Test
    public void shouldFailOpenForMisconfiguredFixedWindow() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);

        // null rate limit
        assertThat(registry.tryAcquire(null, "k").allowed, is(true));
        // missing window
        RateLimit noWindow = rateLimit().withName("x").withLimit(1);
        assertThat(registry.tryAcquire(noWindow, "k").allowed, is(true));
        assertThat(registry.tryAcquire(noWindow, "k").allowed, is(true));
        // missing limit
        RateLimit noLimit = rateLimit().withName("y").withWindowMillis(60_000L);
        assertThat(registry.tryAcquire(noLimit, "k").allowed, is(true));
        // null name AND null fallback key -> fail open
        RateLimit ok = fixedWindow(null, 1, 60_000);
        assertThat(registry.tryAcquire(ok, null).allowed, is(true));
        assertThat(registry.tryAcquire(ok, null).allowed, is(true));
    }

    // --- token bucket ---

    @Test
    public void shouldAllowTokenBucketBurstThenRejectThenRefill() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        // burst of 2, refill 1 token/second
        RateLimit rl = tokenBucket("bkt", 2, 1.0);

        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // consume 1 (2 -> 1)
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // consume 1 (1 -> 0)
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false));  // empty -> reject

        clock.advance(1_000);                                         // refill 1 token
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));   // 1 available -> allow
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false));  // empty again
    }

    @Test
    public void shouldCapTokenBucketAtBurst() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit rl = tokenBucket("bkt", 2, 100.0); // fast refill

        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));  // 2 -> 1
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));  // 1 -> 0
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false)); // 0 -> reject

        clock.advance(60_000);                                       // would refill far past burst, but capped at 2
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));  // 2 -> 1
        assertThat(registry.tryAcquire(rl, "k").allowed, is(true));  // 1 -> 0
        assertThat(registry.tryAcquire(rl, "k").allowed, is(false)); // capped: only 2, not 600
    }

    @Test
    public void shouldReportBurstAsLimitForTokenBucket() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit rl = tokenBucket("bkt", 5, 1.0);

        RateLimitRegistry.Decision d = registry.tryAcquire(rl, "k");
        assertThat(d.allowed, is(true));
        assertThat(d.limit, is(5L)); // X-RateLimit-Limit = burst
        assertThat(d.remaining, is(4L));
    }

    @Test
    public void shouldFailOpenForMisconfiguredTokenBucket() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);

        RateLimit noBurst = rateLimit().withName("x").withAlgorithm(RateLimit.Algorithm.TOKEN_BUCKET).withRefillPerSecond(1.0);
        assertThat(registry.tryAcquire(noBurst, "k").allowed, is(true));
        assertThat(registry.tryAcquire(noBurst, "k").allowed, is(true));

        RateLimit noRefill = rateLimit().withName("y").withAlgorithm(RateLimit.Algorithm.TOKEN_BUCKET).withBurst(2L);
        assertThat(registry.tryAcquire(noRefill, "k").allowed, is(true));
    }

    // --- cap ---

    @Test
    public void shouldFailOpenForNewKeyWhenCapReached() {
        // shrink the cap so the test is fast and isolated
        int previous = org.mockserver.configuration.ConfigurationProperties.rateLimitMaxNamedQuotas();
        try {
            org.mockserver.configuration.ConfigurationProperties.rateLimitMaxNamedQuotas(2);
            FakeClock clock = new FakeClock();
            RateLimitRegistry registry = new RateLimitRegistry(clock::get);
            RateLimit rl = rateLimit().withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW).withLimit(1).withWindowMillis(60_000L);

            // two distinct keys fill the cap
            assertThat(registry.tryAcquire(rl, "a").allowed, is(true));
            assertThat(registry.tryAcquire(rl, "b").allowed, is(true));
            // a THIRD new key fails open (allowed) because the cap is reached
            assertThat(registry.tryAcquire(rl, "c").allowed, is(true));
            // but the third key was NOT tracked -> a repeat is still allowed (not counted)
            assertThat(registry.tryAcquire(rl, "c").allowed, is(true));
            // an EXISTING key is still enforced
            assertThat(registry.tryAcquire(rl, "a").allowed, is(false));
        } finally {
            org.mockserver.configuration.ConfigurationProperties.rateLimitMaxNamedQuotas(previous);
        }
    }

    // --- reset ---

    @Test
    public void shouldClearStateOnReset() {
        FakeClock clock = new FakeClock();
        RateLimitRegistry registry = new RateLimitRegistry(clock::get);
        RateLimit fw = fixedWindow("acct", 1, 60_000);
        RateLimit tb = tokenBucket("bkt", 1, 0.001);

        assertThat(registry.tryAcquire(fw, "k").allowed, is(true));
        assertThat(registry.tryAcquire(fw, "k").allowed, is(false));
        assertThat(registry.tryAcquire(tb, "k").allowed, is(true));
        assertThat(registry.tryAcquire(tb, "k").allowed, is(false));

        registry.reset();

        assertThat(registry.tryAcquire(fw, "k").allowed, is(true));  // window cleared
        assertThat(registry.tryAcquire(tb, "k").allowed, is(true));  // bucket cleared
    }
}
