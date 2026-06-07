package org.mockserver.state.contract;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
import static org.junit.Assert.*;

/**
 * Shared contract test that exercises the full {@link KeyValueStore} SPI
 * against any implementation. Subclasses provide a concrete store via
 * {@link #createStore()} and tear it down via {@link #destroyStore()}.
 * <p>
 * Tested operations: get, put, compareAndSet (success + stale-version
 * failure), compareAndRemove (success + stale-version failure), remove,
 * entries (stream), size, clear, and version monotonicity.
 * <p>
 * Written with JUnit 4 so it can live in mockserver-core's test tree
 * (which uses JUnit 4). The infinispan module has its own JUnit 5 copy
 * (org.mockserver.state.infinispan.KeyValueStoreContract). Both copies
 * must be kept in sync.
 */
public abstract class KeyValueStoreContract {

    protected KeyValueStore<String> store;

    /**
     * Create a fresh {@link KeyValueStore} for each test method.
     */
    protected abstract KeyValueStore<String> createStore();

    /**
     * Tear down / release resources for the store created by {@link #createStore()}.
     * Default implementation is a no-op.
     */
    protected void destroyStore() {
        // no-op by default
    }

    @Before
    public void setUp() {
        store = createStore();
    }

    @After
    public void tearDown() {
        destroyStore();
    }

    // ---- put / get ----

    @Test
    public void shouldPutAndGet() {
        long version = store.put("key1", "value1");
        assertThat(version, greaterThanOrEqualTo(1L));

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("value1"));
        assertThat(result.get().getVersion(), greaterThanOrEqualTo(1L));
    }

    @Test
    public void shouldReturnEmptyForMissingKey() {
        Optional<Versioned<String>> result = store.get("missing");
        assertFalse(result.isPresent());
    }

    @Test
    public void shouldIncrementVersionOnUpdate() {
        long v1 = store.put("key1", "v1");
        long v2 = store.put("key1", "v2");
        assertThat(v2, greaterThan(v1));

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("v2"));
        assertThat(result.get().getVersion(), is(v2));
    }

    // ---- compareAndSet ----

    @Test
    public void shouldCompareAndSetSuccessfully() {
        long v1 = store.put("key1", "v1");
        boolean success = store.compareAndSet("key1", v1, "v2");
        assertTrue(success);

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("v2"));
        assertThat(result.get().getVersion(), greaterThan(v1));
    }

    @Test
    public void shouldFailCompareAndSetWithWrongVersion() {
        store.put("key1", "v1");
        boolean success = store.compareAndSet("key1", 999L, "v2");
        assertFalse(success);

        // Value unchanged
        assertThat(store.get("key1").get().getValue(), is("v1"));
    }

    @Test
    public void shouldFailCompareAndSetForMissingKey() {
        boolean success = store.compareAndSet("missing", 1L, "v1");
        assertFalse(success);
    }

    // ---- compareAndRemove ----

    @Test
    public void shouldCompareAndRemoveSuccessfully() {
        long v1 = store.put("key1", "v1");
        boolean success = store.compareAndRemove("key1", v1);
        assertTrue(success);
        assertFalse(store.get("key1").isPresent());
    }

    @Test
    public void shouldFailCompareAndRemoveWithWrongVersion() {
        store.put("key1", "v1");
        boolean success = store.compareAndRemove("key1", 999L);
        assertFalse(success);
        assertTrue(store.get("key1").isPresent());
    }

    // ---- remove ----

    @Test
    public void shouldRemoveUnconditionally() {
        store.put("key1", "v1");
        assertTrue(store.remove("key1"));
        assertFalse(store.get("key1").isPresent());
    }

    @Test
    public void shouldReturnFalseForRemovingMissingKey() {
        assertFalse(store.remove("missing"));
    }

    // ---- size ----

    @Test
    public void shouldReturnCorrectSize() {
        assertThat(store.size(), is(0));
        store.put("a", "1");
        store.put("b", "2");
        assertThat(store.size(), is(2));
    }

    // ---- clear ----

    @Test
    public void shouldClear() {
        store.put("a", "1");
        store.put("b", "2");
        store.clear();
        assertThat(store.size(), is(0));
        assertFalse(store.get("a").isPresent());
        assertFalse(store.get("b").isPresent());
    }

    // ---- entries (stream) ----

    @Test
    public void shouldStreamEntries() {
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
    public void shouldStreamEntriesWithCorrectValues() {
        store.put("x", "hello");
        store.put("y", "world");

        List<KeyValueStore.Entry<String>> entries = store.entries()
            .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
            .collect(Collectors.toList());
        assertThat(entries, hasSize(2));
        assertThat(entries.get(0).getValue(), is("hello"));
        assertThat(entries.get(1).getValue(), is("world"));
    }

    // ---- version monotonicity ----

    @Test
    public void shouldHaveMonotonicallyIncreasingVersions() {
        long v1 = store.put("k", "a");
        long v2 = store.put("k", "b");
        long v3 = store.put("k", "c");
        assertThat(v2, greaterThan(v1));
        assertThat(v3, greaterThan(v2));
    }

    @Test
    public void shouldAssignFreshVersionAfterRemoveAndReinsert() {
        store.put("k", "a");
        store.remove("k");
        long v2 = store.put("k", "b");
        // After re-insertion, the version should be at least 1
        assertThat(v2, greaterThanOrEqualTo(1L));
    }

    // ---- invalidation listener ----

    // ---- CAS false-success regression ----

    /**
     * Regression test: put("k","a") creates version 1. A subsequent
     * compareAndSet("k", 0, "x") with a stale expectedVersion=0 MUST
     * return false and leave the value unchanged at "a" / version 1.
     * <p>
     * Before the fix, version-based success detection
     * ({@code result.getVersion() == expectedVersion + 1}) falsely
     * reported success because the existing version (1) already equalled
     * expectedVersion(0) + 1, even though no mutation occurred.
     */
    @Test
    public void shouldNotFalselySucceedCompareAndSetWhenExistingVersionEqualsExpectedPlusOne() {
        long v1 = store.put("k", "a");
        assertThat("put should return version 1", v1, is(1L));

        // CAS with stale expectedVersion=0 — existing version is 1 which
        // equals expectedVersion+1, so the old detection would false-positive.
        boolean success = store.compareAndSet("k", 0L, "x");
        assertFalse(success);

        // Value and version must be unchanged
        Optional<Versioned<String>> result = store.get("k");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("a"));
        assertThat(result.get().getVersion(), is(1L));
    }

    @Test
    public void shouldFireInvalidationListenerOnPut() {
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
    public void shouldFireInvalidationListenerOnClear() {
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
