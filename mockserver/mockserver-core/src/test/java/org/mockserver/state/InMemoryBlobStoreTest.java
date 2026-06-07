package org.mockserver.state;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class InMemoryBlobStoreTest {

    private InMemoryBlobStore store;

    @Before
    public void setUp() {
        store = new InMemoryBlobStore();
    }

    @Test
    public void shouldPutAndGet() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, String> meta = new HashMap<>();
        meta.put("type", "text");
        store.put("key1", data, meta);

        Optional<Blob> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getKey(), is("key1"));
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("hello"));
        assertThat(result.get().getMetadata().get("type"), is("text"));
    }

    @Test
    public void shouldReturnEmptyForMissingKey() {
        assertFalse(store.get("missing").isPresent());
    }

    @Test
    public void shouldOverwriteExistingBlob() {
        store.put("key1", "v1".getBytes(), Collections.emptyMap());
        store.put("key1", "v2".getBytes(), Collections.emptyMap());

        assertThat(new String(store.get("key1").get().getData()), is("v2"));
    }

    @Test
    public void shouldListByPrefix() {
        store.put("expectations/e1.json", "{}".getBytes(), Collections.emptyMap());
        store.put("expectations/e2.json", "{}".getBytes(), Collections.emptyMap());
        store.put("cassettes/c1.json", "{}".getBytes(), Collections.emptyMap());

        List<String> result = store.list("expectations/");
        assertThat(result, hasSize(2));
        assertThat(result, containsInAnyOrder("expectations/e1.json", "expectations/e2.json"));
    }

    @Test
    public void shouldReturnEmptyListForNonMatchingPrefix() {
        store.put("a/b", "data".getBytes(), Collections.emptyMap());
        assertThat(store.list("x/"), is(empty()));
    }

    @Test
    public void shouldDeleteBlob() {
        store.put("key1", "data".getBytes(), Collections.emptyMap());
        assertTrue(store.delete("key1"));
        assertFalse(store.get("key1").isPresent());
    }

    @Test
    public void shouldReturnFalseForDeletingMissingKey() {
        assertFalse(store.delete("missing"));
    }
}
