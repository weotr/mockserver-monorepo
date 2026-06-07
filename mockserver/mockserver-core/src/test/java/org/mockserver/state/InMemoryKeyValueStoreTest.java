package org.mockserver.state;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class InMemoryKeyValueStoreTest {

    private InMemoryKeyValueStore<String> store;

    @Before
    public void setUp() {
        store = new InMemoryKeyValueStore<>();
    }

    @Test
    public void shouldPutAndGet() {
        long version = store.put("key1", "value1");
        assertThat(version, is(1L));

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("value1"));
        assertThat(result.get().getVersion(), is(1L));
    }

    @Test
    public void shouldReturnEmptyForMissingKey() {
        Optional<Versioned<String>> result = store.get("missing");
        assertFalse(result.isPresent());
    }

    @Test
    public void shouldIncrementVersionOnUpdate() {
        store.put("key1", "v1");
        long ver2 = store.put("key1", "v2");
        assertThat(ver2, is(2L));

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("v2"));
        assertThat(result.get().getVersion(), is(2L));
    }

    @Test
    public void shouldCompareAndSetSuccessfully() {
        store.put("key1", "v1");
        boolean success = store.compareAndSet("key1", 1L, "v2");
        assertTrue(success);

        Optional<Versioned<String>> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("v2"));
        assertThat(result.get().getVersion(), is(2L));
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

    @Test
    public void shouldCompareAndRemoveSuccessfully() {
        store.put("key1", "v1");
        boolean success = store.compareAndRemove("key1", 1L);
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

    @Test
    public void shouldReturnCorrectSize() {
        assertThat(store.size(), is(0));
        store.put("a", "1");
        store.put("b", "2");
        assertThat(store.size(), is(2));
    }

    @Test
    public void shouldClear() {
        store.put("a", "1");
        store.put("b", "2");
        store.clear();
        assertThat(store.size(), is(0));
    }

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
    public void shouldFireInvalidationListenerOnChange() {
        AtomicInteger changedCount = new AtomicInteger(0);
        AtomicInteger clearedCount = new AtomicInteger(0);
        store.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                changedCount.incrementAndGet();
            }

            @Override
            public void onCleared() {
                clearedCount.incrementAndGet();
            }
        });

        store.put("a", "1");
        assertThat(changedCount.get(), is(1));

        store.put("a", "2");
        assertThat(changedCount.get(), is(2));

        store.remove("a");
        assertThat(changedCount.get(), is(3));

        store.put("b", "1");
        store.clear();
        assertThat(clearedCount.get(), is(1));
    }
}
