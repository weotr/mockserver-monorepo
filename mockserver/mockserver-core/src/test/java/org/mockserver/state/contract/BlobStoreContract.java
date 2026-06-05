package org.mockserver.state.contract;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Shared contract test for the {@link BlobStore} SPI. Exercises
 * put/get/overwrite/list-by-prefix/delete/missing-key/metadata round-trip
 * against any implementation.
 * <p>
 * Subclasses provide a concrete store via {@link #createStore()} and
 * tear it down via {@link #destroyStore()}.
 * <p>
 * Uses JUnit 4 to match the existing core test conventions (JUnit 4
 * with Hamcrest matchers).
 */
public abstract class BlobStoreContract {

    protected BlobStore store;

    /**
     * Create a fresh BlobStore instance for each test.
     */
    protected abstract BlobStore createStore();

    /**
     * Tear down the store after each test (optional override).
     */
    protected void destroyStore() {
    }

    @Before
    public void setUp() {
        store = createStore();
    }

    @After
    public void tearDown() {
        destroyStore();
    }

    // --- put / get round-trip ---

    @Test
    public void shouldPutAndGet() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        Map<String, String> meta = new HashMap<>();
        meta.put("type", "text");
        meta.put("source", "test");
        store.put("key1", data, meta);

        Optional<Blob> result = store.get("key1");
        assertTrue("blob should be present", result.isPresent());
        assertThat(result.get().getKey(), is("key1"));
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("hello world"));
        assertThat(result.get().getMetadata().get("type"), is("text"));
        assertThat(result.get().getMetadata().get("source"), is("test"));
    }

    // --- missing key ---

    @Test
    public void shouldReturnEmptyForMissingKey() {
        assertFalse("missing key should return empty", store.get("nonexistent").isPresent());
    }

    // --- overwrite ---

    @Test
    public void shouldOverwriteExistingBlob() {
        store.put("key1", "v1".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        store.put("key1", "v2".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        Optional<Blob> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("v2"));
    }

    @Test
    public void shouldOverwriteMetadata() {
        Map<String, String> meta1 = new HashMap<>();
        meta1.put("version", "1");
        store.put("key1", "data".getBytes(StandardCharsets.UTF_8), meta1);

        Map<String, String> meta2 = new HashMap<>();
        meta2.put("version", "2");
        meta2.put("extra", "yes");
        store.put("key1", "data".getBytes(StandardCharsets.UTF_8), meta2);

        Optional<Blob> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getMetadata().get("version"), is("2"));
        assertThat(result.get().getMetadata().get("extra"), is("yes"));
    }

    // --- list by prefix ---

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
    public void shouldListEmptyStoreWithPrefix() {
        assertThat(store.list("any/"), is(empty()));
    }

    // --- delete ---

    @Test
    public void shouldDeleteBlob() {
        store.put("key1", "data".getBytes(), Collections.emptyMap());
        assertTrue("delete should return true for existing key", store.delete("key1"));
        assertFalse("get after delete should return empty", store.get("key1").isPresent());
    }

    @Test
    public void shouldReturnFalseForDeletingMissingKey() {
        assertFalse("delete of missing key should return false", store.delete("missing"));
    }

    // --- metadata round-trip ---

    @Test
    public void shouldRoundTripEmptyMetadata() {
        store.put("key1", "data".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        Optional<Blob> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getMetadata(), is(notNullValue()));
        // empty metadata should come back as empty (not null)
        assertTrue("metadata should be empty or have no entries",
            result.get().getMetadata().isEmpty());
    }

    @Test
    public void shouldHandleMetadataWithSpecialCharacters() {
        Map<String, String> meta = new HashMap<>();
        meta.put("description", "test=value with spaces");
        meta.put("path", "a/b/c");
        store.put("key1", "data".getBytes(StandardCharsets.UTF_8), meta);

        Optional<Blob> result = store.get("key1");
        assertTrue(result.isPresent());
        assertThat(result.get().getMetadata().get("description"), is("test=value with spaces"));
        assertThat(result.get().getMetadata().get("path"), is("a/b/c"));
    }

    /**
     * Verifies that metadata keys which differ only in a non-alphanumeric
     * character survive a put/get round-trip with the EXACT original key names
     * and do NOT collide. {@code x-custom-type} and {@code x_custom_type} are the
     * canonical collision case: a backend that sanitises {@code -} to {@code _}
     * (as the Azure backend previously did) would map both keys to the same
     * stored key, silently losing one. Both keys are also valid HTTP header
     * tokens, so they are representable on every backend (S3 stores metadata as
     * {@code x-amz-meta-*} headers, which forbid characters like {@code =}).
     */
    @Test
    public void shouldRoundTripMetadataKeysWithNonAlphanumericCharacters() {
        Map<String, String> meta = new HashMap<>();
        meta.put("x-custom-type", "hyphenated");
        meta.put("x_custom_type", "underscored");
        meta.put("simple", "value");

        store.put("roundtrip-meta", "payload".getBytes(StandardCharsets.UTF_8), meta);

        Optional<Blob> result = store.get("roundtrip-meta");
        assertTrue("blob should be present", result.isPresent());

        Map<String, String> retrievedMeta = result.get().getMetadata();
        assertThat("hyphenated key should round-trip exactly (no collision)",
            retrievedMeta.get("x-custom-type"), is("hyphenated"));
        assertThat("underscored key should round-trip exactly (no collision)",
            retrievedMeta.get("x_custom_type"), is("underscored"));
        assertThat("simple alphanumeric key should round-trip",
            retrievedMeta.get("simple"), is("value"));
        assertThat("metadata should have exactly 3 entries (no key collision)",
            retrievedMeta.size(), is(3));
    }

    // --- empty and large data ---

    @Test
    public void shouldHandleEmptyData() {
        store.put("empty", new byte[0], Collections.emptyMap());

        Optional<Blob> result = store.get("empty");
        assertTrue(result.isPresent());
        assertThat(result.get().getData().length, is(0));
    }

    @Test
    public void shouldHandleBinaryData() {
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) {
            binary[i] = (byte) i;
        }
        store.put("binary", binary, Collections.emptyMap());

        Optional<Blob> result = store.get("binary");
        assertTrue(result.isPresent());
        assertArrayEquals(binary, result.get().getData());
    }

    // --- delete then get ---

    @Test
    public void shouldNotFindDeletedBlob() {
        store.put("key1", "data".getBytes(), Collections.emptyMap());
        store.delete("key1");
        assertFalse(store.get("key1").isPresent());
        assertThat(store.list("key"), is(empty()));
    }

    // --- nested key prefixes ---

    @Test
    public void shouldListNestedKeyPrefixes() {
        store.put("a/b/c1", "d1".getBytes(), Collections.emptyMap());
        store.put("a/b/c2", "d2".getBytes(), Collections.emptyMap());
        store.put("a/d/c1", "d3".getBytes(), Collections.emptyMap());

        List<String> result = store.list("a/b/");
        assertThat(result, hasSize(2));
        assertThat(result, containsInAnyOrder("a/b/c1", "a/b/c2"));
    }
}
