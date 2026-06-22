package org.mockserver.llm;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LlmQuotaRegistryTest {

    /** A controllable clock so window behaviour is deterministic without sleeping. */
    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000L);

        long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    @Test
    public void shouldAllowRequestsUpToLimitThenReject() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 3, 60_000), is(true));   // 1
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(true));   // 2
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(true));   // 3
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(false));  // 4 -> over
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(false));  // 5 -> still over
    }

    @Test
    public void shouldResetCountWhenWindowElapses() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));   // 1
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));  // 2 -> over

        clock.advance(60_000);                                          // window elapses
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));   // fresh window
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));  // over again
    }

    @Test
    public void shouldNotResetBeforeWindowElapses() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));
        clock.advance(59_999);                                          // still inside the window
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));
    }

    @Test
    public void shouldKeepSeparateCountersPerName() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("a", 1, 60_000), is(true));
        assertThat(registry.tryAcquire("a", 1, 60_000), is(false));
        // a different name has its own counter
        assertThat(registry.tryAcquire("b", 1, 60_000), is(true));
    }

    @Test
    public void shouldShareOneCounterAcrossSameName() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("shared", 2, 60_000), is(true));
        assertThat(registry.tryAcquire("shared", 2, 60_000), is(true));
        assertThat(registry.tryAcquire("shared", 2, 60_000), is(false));
    }

    @Test
    public void shouldFailOpenForMisconfiguredQuota() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire(null, 1, 60_000), is(true));
        assertThat(registry.tryAcquire("x", -1, 60_000), is(true));
        assertThat(registry.tryAcquire("x", 1, 0), is(true));
    }

    @Test
    public void shouldClearStateOnReset() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));
        registry.reset();
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));   // counter cleared
    }

    @Test
    public void shouldBeThreadSafeUnderConcurrentAcquire() throws Exception {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        int threads = 16;
        int perThread = 100;
        int limit = 500;
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (registry.tryAcquire("c", limit, 600_000)) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS), is(true));

        // exactly `limit` of the 1600 attempts may be allowed — no lost/duplicated increments
        assertThat(allowed.get(), is(limit));
    }

    // --- token-based (amount) overload ---

    @Test
    public void shouldSumAmountsAndRejectWhenLimitCrossed() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        // limit = 100 tokens; charge 40, 40, 30 -> 40+40=80 ok, 80+30=110 over
        assertThat(registry.tryAcquire("tok", 100L, 60_000, 40L), is(true));   // total = 40
        assertThat(registry.tryAcquire("tok", 100L, 60_000, 40L), is(true));   // total = 80
        assertThat(registry.tryAcquire("tok", 100L, 60_000, 30L), is(false));  // total = 110 > 100
    }

    @Test
    public void shouldAllowExactlyAtLimit() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("tok", 100L, 60_000, 100L), is(true));  // exactly at limit
        assertThat(registry.tryAcquire("tok", 100L, 60_000, 1L), is(false));   // one over
    }

    @Test
    public void shouldResetTokenWindowAfterExpiry() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("tok", 50L, 60_000, 50L), is(true));    // total = 50 (at limit)
        assertThat(registry.tryAcquire("tok", 50L, 60_000, 1L), is(false));    // over

        clock.advance(60_000);                                                  // window expires
        assertThat(registry.tryAcquire("tok", 50L, 60_000, 30L), is(true));    // fresh window
    }

    @Test
    public void shouldDelegateFromRequestCountOverloadToAmountOverload() {
        // Verify that the int overload (amount=1) still works correctly
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("req", 2, 60_000), is(true));   // 1
        assertThat(registry.tryAcquire("req", 2, 60_000), is(true));   // 2
        assertThat(registry.tryAcquire("req", 2, 60_000), is(false));  // 3 -> over
    }

    @Test
    public void shouldFailOpenForNegativeAmount() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        // negative amount is misconfigured -> fail open
        assertThat(registry.tryAcquire("tok", 100L, 60_000, -5L), is(true));
    }

    @Test
    public void shouldFailOpenForBadInputOnAmountOverload() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire(null, 100L, 60_000, 10L), is(true));
        assertThat(registry.tryAcquire("x", -1L, 60_000, 10L), is(true));
        assertThat(registry.tryAcquire("x", 100L, 0, 10L), is(true));
    }

    @Test
    public void shouldAllowZeroAmount() {
        FakeClock clock = new FakeClock();
        LlmQuotaRegistry registry = new LlmQuotaRegistry(clock::get);

        // amount=0 should be allowed and not consume any quota
        assertThat(registry.tryAcquire("tok", 10L, 60_000, 0L), is(true));   // total = 0
        assertThat(registry.tryAcquire("tok", 10L, 60_000, 10L), is(true));  // total = 10 (at limit)
        assertThat(registry.tryAcquire("tok", 10L, 60_000, 1L), is(false));  // total = 11 > 10
    }
}
