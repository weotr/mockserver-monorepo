package org.mockserver.cache;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class LRUCacheTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(LRUCacheTest.class);

    @Test
    public void shouldReturnCachedObjects() {
        // given
        LRUCache<String, Object> lruCache = new LRUCache<>(mockServerLogger, 5, MINUTES.toMillis(10));

        // when
        lruCache.put("one", "a");
        lruCache.put("two", "b");
        lruCache.put("one", "c");

        // then
        assertThat(lruCache.get("one"), is("c"));
        assertThat(lruCache.get("two"), is("b"));
    }

    @Test
    public void shouldNotCacheIfGloballyDisabled() {
        try {
            // given
            LRUCache.allCachesEnabled(false);
            LRUCache<String, Object> lruCache = new LRUCache<>(mockServerLogger, 5, MINUTES.toMillis(10));

            // when
            lruCache.put("one", "a");
            lruCache.put("two", "b");

            // then
            assertThat(lruCache.get("one"), nullValue());
            assertThat(lruCache.get("two"), nullValue());
        } finally {
            LRUCache.allCachesEnabled(true);
        }
    }

    @Test
    public void shouldExpireItems() throws InterruptedException {
        // given
        LRUCache<String, Object> lruCache = new LRUCache<>(mockServerLogger, 5, SECONDS.toMillis(1));

        // when
        lruCache.put("one", "a");
        lruCache.put("two", "b");

        // then
        assertThat(lruCache.get("one"), is("a"));
        assertThat(lruCache.get("two"), is("b"));

        // when
        SECONDS.sleep(2L);

        // then
        assertThat(lruCache.get("one"), nullValue());
        assertThat(lruCache.get("two"), nullValue());
    }

    @Test
    public void shouldExtendExpiryOnGet() throws InterruptedException {
        // given
        LRUCache<String, Object> lruCache = new LRUCache<>(mockServerLogger, 5, SECONDS.toMillis(5));

        // when
        lruCache.put("one", "a");
        lruCache.put("two", "b");

        // then
        assertThat(lruCache.get("one"), is("a"));
        assertThat(lruCache.get("two"), is("b"));

        // when
        SECONDS.sleep(3L);

        // then
        assertThat(lruCache.get("one"), is("a"));
        assertThat(lruCache.get("two"), is("b"));

        // when
        SECONDS.sleep(3L);

        // then
        assertThat(lruCache.get("one"), is("a"));
        assertThat(lruCache.get("two"), is("b"));
    }

    @Test
    public void shouldLimitCacheGlobally() {
        try {
            // given
            LRUCache.setMaxSizeOverride(3);
            LRUCache<String, Object> lruCache = new LRUCache<>(mockServerLogger, 5, SECONDS.toMillis(3));

            // when
            lruCache.put("one", "a");
            lruCache.put("two", "b");
            lruCache.put("three", "c");
            lruCache.put("four", "d");

            // then
            assertThat(lruCache.get("four"), is("d"));
            assertThat(lruCache.get("two"), is("b"));
            assertThat(lruCache.get("one"), is(nullValue()));
        } finally {
            LRUCache.setMaxSizeOverride(0);
        }
    }

    @Test
    public void shouldLimitCacheLocally() {
        // given
        LRUCache<String, Object> lruCache = new LRUCache<>(mockServerLogger, 4, SECONDS.toMillis(3));

        // when
        lruCache.put("one", "a");
        lruCache.put("two", "b");
        lruCache.put("three", "c");
        lruCache.put("four", "d");
        lruCache.put("five", "e");

        // then
        assertThat(lruCache.get("five"), is("e"));
        assertThat(lruCache.get("two"), is("b"));
        assertThat(lruCache.get("one"), is(nullValue()));
    }

    @Test
    public void shouldComputeAndCacheOnceOnGetOrCompute() {
        // given
        LRUCache<String, String> lruCache = new LRUCache<>(mockServerLogger, 5, MINUTES.toMillis(10));
        java.util.concurrent.atomic.AtomicInteger computeCount = new java.util.concurrent.atomic.AtomicInteger();

        // when - same key looked up twice
        String first = lruCache.getOrCompute("k", k -> {
            computeCount.incrementAndGet();
            return "v";
        });
        String second = lruCache.getOrCompute("k", k -> {
            computeCount.incrementAndGet();
            return "other";
        });

        // then - computed exactly once; both callers get the first value; it is now in the cache
        assertThat(first, is("v"));
        assertThat(second, is("v"));
        assertThat(computeCount.get(), is(1));
        assertThat(lruCache.get("k"), is("v"));
    }

    @Test
    public void getOrComputeShouldRespectLocalSizeBound() {
        // given - capacity 2
        LRUCache<String, String> lruCache = new LRUCache<>(mockServerLogger, 2, MINUTES.toMillis(10));

        // when - three distinct keys computed
        lruCache.getOrCompute("one", k -> "a");
        lruCache.getOrCompute("two", k -> "b");
        lruCache.getOrCompute("three", k -> "c");

        // then - the just-inserted key is retained (never self-evicted) and the bound is enforced
        // (oldest evicted); the queue and map stay in lock-step so the evicted key is genuinely gone
        assertThat(lruCache.get("three"), is("c"));
        assertThat(lruCache.get("one"), is(nullValue()));
    }

    @Test
    public void getOrComputeShouldRecomputeAfterEviction() {
        // given - capacity 1, so each new key evicts the previous one
        LRUCache<String, String> lruCache = new LRUCache<>(mockServerLogger, 1, MINUTES.toMillis(10));
        java.util.concurrent.atomic.AtomicInteger computeCount = new java.util.concurrent.atomic.AtomicInteger();

        // when - "a" computed, evicted by "b", then "a" requested again
        lruCache.getOrCompute("a", k -> "va" + computeCount.incrementAndGet());
        lruCache.getOrCompute("b", k -> "vb" + computeCount.incrementAndGet());
        String aAgain = lruCache.getOrCompute("a", k -> "va" + computeCount.incrementAndGet());

        // then - "a" was genuinely evicted (queue/map in lock-step), so it recomputes rather than
        // returning a stale still-mapped value: 3 computations total
        assertThat(aAgain, is("va3"));
        assertThat(computeCount.get(), is(3));
        // and the bound holds: only the most-recent key remains
        assertThat(lruCache.get("a"), is("va3"));
        assertThat(lruCache.get("b"), is(nullValue()));
    }

    @Test
    public void shouldClearGlobally() {
        // given
        LRUCache<String, Object> lruCacheOne = new LRUCache<>(mockServerLogger, 5, MINUTES.toMillis(10));
        LRUCache<String, Object> lruCacheTwo = new LRUCache<>(mockServerLogger, 5, MINUTES.toMillis(10));
        LRUCache<String, Object> lruCacheThree = new LRUCache<>(mockServerLogger, 5, MINUTES.toMillis(10));
        lruCacheOne.put("one", "a");
        lruCacheTwo.put("one", "a");
        lruCacheThree.put("one", "a");

        // then
        assertThat(lruCacheOne.get("one"), is("a"));
        assertThat(lruCacheTwo.get("one"), is("a"));
        assertThat(lruCacheThree.get("one"), is("a"));

        // when
        LRUCache.clearAllCaches();

        // then
        assertThat(lruCacheOne.get("one"), is(nullValue()));
        assertThat(lruCacheTwo.get("one"), is(nullValue()));
        assertThat(lruCacheThree.get("one"), is(nullValue()));
    }

}