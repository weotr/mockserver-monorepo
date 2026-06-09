package org.mockserver.state.infinispan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.Versioned;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract test that exercises the full {@link KeyValueStore} SPI
 * against any implementation. Subclasses provide a concrete store via
 * {@link #createStore()} and tear it down via {@link #destroyStore()}.
 * <p>
 * This is a JUnit 5 copy of the contract in mockserver-core's test tree
 * (org.mockserver.state.contract.KeyValueStoreContract, JUnit 4). It is
 * duplicated here because core does not publish a test-jar. Both copies
 * must be kept in sync.
 */
public abstract class KeyValueStoreContract {

    protected KeyValueStore<String> store;

    protected abstract KeyValueStore<String> createStore();

    protected void destroyStore() {
    }

    @BeforeEach
    void setUp() {
        store = createStore();
    }

    @AfterEach
    void tearDown() {
        destroyStore();
    }

    @Test
    void shouldPutAndGet() {
        long version = store.put("key1", "value1");
        assertThat(version, greaterThanOrEqualTo(1L));

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("value1"));
        assertThat(result.get().getVersion(), greaterThanOrEqualTo(1L));
    }

    @Test
    void shouldReturnEmptyForMissingKey() {
        Optional<Versioned<String>> result = store.get("missing");
        assertFalse(result.isPresent());
    }

    @Test
    void shouldIncrementVersionOnUpdate() {
        long v1 = store.put("key1", "v1");
        long v2 = store.put("key1", "v2");
        assertThat(v2, greaterThan(v1));

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("v2"));
        assertThat(result.get().getVersion(), is(v2));
    }

    @Test
    void shouldCompareAndSetSuccessfully() {
        long v1 = store.put("key1", "v1");
        boolean success = store.compareAndSet("key1", v1, "v2");
        assertTrue(success);

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("v2"));
        assertThat(result.get().getVersion(), greaterThan(v1));
    }

    @Test
    void shouldFailCompareAndSetWithWrongVersion() {
        store.put("key1", "v1");
        boolean success = store.compareAndSet("key1", 999L, "v2");
        assertFalse(success);
        assertThat(store.get("key1").get().getValue(), is("v1"));
    }

    @Test
    void shouldFailCompareAndSetForMissingKey() {
        boolean success = store.compareAndSet("missing", 1L, "v1");
        assertFalse(success);
    }

    @Test
    void shouldCompareAndRemoveSuccessfully() {
        long v1 = store.put("key1", "v1");
        boolean success = store.compareAndRemove("key1", v1);
        assertTrue(success);
        assertFalse(store.get("key1").isPresent());
    }

    @Test
    void shouldFailCompareAndRemoveWithWrongVersion() {
        store.put("key1", "v1");
        boolean success = store.compareAndRemove("key1", 999L);
        assertFalse(success);
        assertTrue(store.get("key1").isPresent());
    }

    @Test
    void shouldRemoveUnconditionally() {
        store.put("key1", "v1");
        assertTrue(store.remove("key1"));
        assertFalse(store.get("key1").isPresent());
    }

    @Test
    void shouldReturnFalseForRemovingMissingKey() {
        assertFalse(store.remove("missing"));
    }

    @Test
    void shouldReturnCorrectSize() {
        assertThat(store.size(), is(0));
        store.put("a", "1");
        store.put("b", "2");
        assertThat(store.size(), is(2));
    }

    @Test
    void shouldClear() {
        store.put("a", "1");
        store.put("b", "2");
        store.clear();
        assertThat(store.size(), is(0));
        assertFalse(store.get("a").isPresent());
        assertFalse(store.get("b").isPresent());
    }

    @Test
    void shouldStreamEntries() {
        store.put("a", "1");
        store.put("b", "2");
        store.put("c", "3");

        List<String> keys = store.entries()
            .map(KeyValueStore.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());
        assertThat(keys, contains("a", "b", "c"));
    }

    @Test
    void shouldStreamEntriesWithCorrectValues() {
        store.put("x", "hello");
        store.put("y", "world");

        List<KeyValueStore.Entry<String>> entries = store.entries()
            .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
            .collect(Collectors.toList());
        assertThat(entries, hasSize(2));
        assertThat(entries.get(0).getValue(), is("hello"));
        assertThat(entries.get(1).getValue(), is("world"));
    }

    @Test
    void shouldHaveMonotonicallyIncreasingVersions() {
        long v1 = store.put("k", "a");
        long v2 = store.put("k", "b");
        long v3 = store.put("k", "c");
        assertThat(v2, greaterThan(v1));
        assertThat(v3, greaterThan(v2));
    }

    @Test
    void shouldAssignFreshVersionAfterRemoveAndReinsert() {
        store.put("k", "a");
        store.remove("k");
        long v2 = store.put("k", "b");
        assertThat(v2, greaterThanOrEqualTo(1L));
    }

    // ---- putIfAbsent ----

    @Test
    void shouldPutIfAbsentWhenKeyDoesNotExist() {
        Optional<Versioned<String>> existing = store.putIfAbsent("newKey", "value1");
        assertFalse(existing.isPresent(), "putIfAbsent on missing key should return empty");

        // Key should now exist with the value we inserted
        Optional<Versioned<String>> result = store.get("newKey");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("value1"));
        assertThat(result.get().getVersion(), greaterThanOrEqualTo(1L));
    }

    @Test
    void shouldNotOverwriteExistingOnPutIfAbsent() {
        store.put("existingKey", "original");

        Optional<Versioned<String>> existing = store.putIfAbsent("existingKey", "shouldNotReplace");
        assertTrue(existing.isPresent(), "putIfAbsent on existing key should return the existing value");
        assertThat(existing.get().getValue(), is("original"));

        // Value must be unchanged
        Optional<Versioned<String>> result = store.get("existingKey");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("original"));
    }

    @Test
    void shouldPutIfAbsentConcurrentlyWithExactlyOneCreator() throws Exception {
        int threadCount = 10;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
        java.util.concurrent.atomic.AtomicInteger creatorCount = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final String value = "thread-" + i;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    Optional<Versioned<String>> existing = store.putIfAbsent("raceKey", value);
                    if (!existing.isPresent()) {
                        creatorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat("exactly one thread should be the creator", creatorCount.get(), is(1));
        // The key should exist with the winner's value
        assertTrue(store.get("raceKey").isPresent());
    }

    // ---- CAS false-success regression ----

    /**
     * Regression test: put("k","a") creates version 1. A subsequent
     * compareAndSet("k", 0, "x") with a stale expectedVersion=0 MUST
     * return false and leave the value unchanged at "a" / version 1.
     * <p>
     * Before the fix, the version-based success detection
     * ({@code result.getVersion() == expectedVersion + 1}) falsely
     * reported success because the existing version (1) already equalled
     * expectedVersion(0) + 1, even though no mutation occurred.
     */
    @Test
    void shouldNotFalselySucceedCompareAndSetWhenExistingVersionEqualsExpectedPlusOne() {
        long v1 = store.put("k", "a");
        assertThat("put should return version 1", v1, is(1L));

        // CAS with stale expectedVersion=0 — existing version is 1 which
        // equals expectedVersion+1, so the old detection would false-positive.
        boolean success = store.compareAndSet("k", 0L, "x");
        assertFalse(success, "compareAndSet with stale version 0 must fail");

        // Value and version must be unchanged
        Optional<Versioned<String>> result = store.get("k");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("a"));
        assertThat(result.get().getVersion(), is(1L));
    }

    @Test
    void shouldFireInvalidationListenerOnPut() {
        AtomicReference<String> lastKey = new AtomicReference<>();
        store.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                lastKey.set(key);
            }

            @Override
            public void onCleared() {
            }
        });

        store.put("a", "1");
        assertThat(lastKey.get(), is("a"));
    }

    @Test
    void shouldFireInvalidationListenerOnClear() {
        AtomicInteger clearedCount = new AtomicInteger(0);
        store.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
            }

            @Override
            public void onCleared() {
                clearedCount.incrementAndGet();
            }
        });

        store.put("a", "1");
        store.clear();
        assertThat(clearedCount.get(), is(1));
    }
}
