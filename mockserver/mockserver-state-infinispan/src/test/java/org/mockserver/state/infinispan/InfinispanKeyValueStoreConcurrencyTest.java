package org.mockserver.state.infinispan;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.state.Versioned;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contention test for {@link InfinispanKeyValueStore#put(String, Object)}
 * CAS retry loop. Spawns multiple threads hammering the same key to
 * exercise the bounded retry logic under real contention.
 */
class InfinispanKeyValueStoreConcurrencyTest {

    private static final int THREAD_COUNT = 8;
    private static final int PUTS_PER_THREAD = 50;
    private static final String CONTENDED_KEY = "contended-key";

    private EmbeddedCacheManager cacheManager;
    private InfinispanKeyValueStore<String> store;

    @BeforeEach
    void setUp() {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        global.nonClusteredDefault();
        global.serialization().allowList().addRegexp(".*");

        ConfigurationBuilder cacheConfig = new ConfigurationBuilder();
        cacheConfig.memory().storage(StorageType.HEAP);

        cacheManager = new DefaultCacheManager(global.build());
        cacheManager.defineConfiguration("contention-test", cacheConfig.build());

        @SuppressWarnings("unchecked")
        Cache<String, VersionedWrapper<String>> cache =
            (Cache<String, VersionedWrapper<String>>) (Cache<?, ?>) cacheManager.getCache("contention-test");
        store = new InfinispanKeyValueStore<>(cache);
    }

    @AfterEach
    void tearDown() {
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }

    @Test
    void shouldHandleConcurrentPutsToSameKeyWithoutErrorOrHang() throws Exception {
        int totalPuts = THREAD_COUNT * PUTS_PER_THREAD;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Long> allVersions = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> allValues = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREAD_COUNT; t++) {
            int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < PUTS_PER_THREAD; i++) {
                        String value = "thread-" + threadId + "-put-" + i;
                        long version = store.put(CONTENDED_KEY, value);
                        allVersions.add(version);
                        allValues.add(value);
                    }
                } catch (Exception e) {
                    errorOccurred.set(true);
                    throw new RuntimeException(e);
                }
            }));
        }

        // Release all threads at once to maximise overlap
        startLatch.countDown();

        // Wait for completion with a generous timeout to detect hangs
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertTrue(finished, "threads did not complete within timeout — possible hang in CAS loop");

        // Check no futures threw
        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get(), "thread threw an exception");
        }
        assertFalse(errorOccurred.get(), "an error occurred in one of the writer threads");

        // (a) Final version == total successful puts
        Optional<Versioned<String>> finalEntry = store.get(CONTENDED_KEY);
        assertTrue(finalEntry.isPresent());
        assertThat("final version should equal total puts",
            finalEntry.get().getVersion(), is((long) totalPuts));

        // (b) All observed versions are unique (no duplicates) and monotonic
        List<Long> sortedVersions = new ArrayList<>(allVersions);
        Collections.sort(sortedVersions);
        assertThat("should have exactly " + totalPuts + " versions",
            sortedVersions, hasSize(totalPuts));
        Set<Long> uniqueVersions = new HashSet<>(allVersions);
        assertThat("no duplicate versions",
            uniqueVersions, hasSize(totalPuts));
        // Versions should be 1..totalPuts (contiguous monotonic)
        for (int i = 0; i < sortedVersions.size(); i++) {
            assertThat("versions should be contiguous from 1",
                sortedVersions.get(i), is((long) (i + 1)));
        }

        // (c) No IllegalStateException occurred (already verified above via errorOccurred)

        // (d) Final stored value is one of the written values
        assertTrue(allValues.contains(finalEntry.get().getValue()),
            "final value should be one of the written values");
    }
}
