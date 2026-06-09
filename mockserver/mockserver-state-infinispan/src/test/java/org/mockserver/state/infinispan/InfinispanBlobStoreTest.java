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
import org.mockserver.state.Blob;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InfinispanBlobStore} mirroring
 * {@code org.mockserver.state.FilesystemBlobStoreTest}: put/get round-trip
 * (byte-identical), list-by-prefix, delete, empty content, and large content.
 * Uses a LOCAL (non-clustered) Infinispan cache — the same setup pattern as
 * {@link InfinispanStateBackendTest}.
 */
class InfinispanBlobStoreTest {

    private EmbeddedCacheManager cacheManager;
    private InfinispanBlobStore store;

    @BeforeEach
    void setUp() {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        global.nonClusteredDefault();
        global.serialization().allowList().addRegexp(".*");

        ConfigurationBuilder cacheConfig = new ConfigurationBuilder();
        cacheConfig.memory().storage(StorageType.HEAP);

        cacheManager = new DefaultCacheManager(global.build());
        cacheManager.defineConfiguration("blobs-test", cacheConfig.build());

        Cache<String, Blob> cache = cacheManager.getCache("blobs-test");
        store = new InfinispanBlobStore(cache);
    }

    @AfterEach
    void tearDown() {
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }

    // --- put / get round-trip ---

    @Test
    void shouldPutAndGetRoundTripByteIdentical() {
        byte[] data = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.put("test/blob1", data, Collections.emptyMap());

        Optional<Blob> result = store.get("test/blob1");
        assertTrue(result.isPresent(), "blob should be present after put");
        assertThat(result.get().getKey(), is("test/blob1"));
        assertArrayEquals(data, result.get().getData(),
            "retrieved data should be byte-for-byte identical to what was stored");
    }

    @Test
    void shouldPreserveMetadata() {
        Map<String, String> meta = new HashMap<>();
        meta.put("type", "expectations");
        meta.put("source", "api");
        byte[] data = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        store.put("meta/blob1", data, meta);

        Optional<Blob> result = store.get("meta/blob1");
        assertTrue(result.isPresent());
        assertThat(result.get().getMetadata().get("type"), is("expectations"));
        assertThat(result.get().getMetadata().get("source"), is("api"));
    }

    @Test
    void shouldOverwriteExistingBlob() {
        store.put("overwrite", "v1".getBytes(), Collections.emptyMap());
        store.put("overwrite", "v2".getBytes(), Collections.emptyMap());

        Optional<Blob> result = store.get("overwrite");
        assertTrue(result.isPresent());
        assertArrayEquals("v2".getBytes(), result.get().getData());
    }

    // --- get (missing key) ---

    @Test
    void shouldReturnEmptyForMissingKey() {
        assertFalse(store.get("nonexistent").isPresent());
    }

    // --- list by prefix ---

    @Test
    void shouldListByPrefix() {
        store.put("expectations/e1.json", "{}".getBytes(), Collections.emptyMap());
        store.put("expectations/e2.json", "{}".getBytes(), Collections.emptyMap());
        store.put("cassettes/c1.json", "{}".getBytes(), Collections.emptyMap());

        List<String> result = store.list("expectations/");
        assertThat(result, hasSize(2));
        assertThat(result, containsInAnyOrder("expectations/e1.json", "expectations/e2.json"));
    }

    @Test
    void shouldReturnEmptyListForNonMatchingPrefix() {
        store.put("a/b.json", "data".getBytes(), Collections.emptyMap());
        assertThat(store.list("x/"), is(empty()));
    }

    @Test
    void shouldListAllKeysWithEmptyPrefix() {
        store.put("a", "1".getBytes(), Collections.emptyMap());
        store.put("b", "2".getBytes(), Collections.emptyMap());

        List<String> result = store.list("");
        assertThat(result, hasSize(2));
        assertThat(result, containsInAnyOrder("a", "b"));
    }

    // --- delete ---

    @Test
    void shouldDeleteBlob() {
        store.put("delete-me", "data".getBytes(), Collections.emptyMap());
        assertTrue(store.delete("delete-me"));
        assertFalse(store.get("delete-me").isPresent());
    }

    @Test
    void shouldReturnFalseWhenDeletingMissingKey() {
        assertFalse(store.delete("nonexistent"));
    }

    @Test
    void shouldNotAffectOtherBlobsOnDelete() {
        store.put("keep", "keep-data".getBytes(), Collections.emptyMap());
        store.put("remove", "remove-data".getBytes(), Collections.emptyMap());

        assertTrue(store.delete("remove"));
        assertTrue(store.get("keep").isPresent());
        assertArrayEquals("keep-data".getBytes(), store.get("keep").get().getData());
    }

    // --- empty content ---

    @Test
    void shouldHandleEmptyData() {
        store.put("empty", new byte[0], Collections.emptyMap());

        Optional<Blob> result = store.get("empty");
        assertTrue(result.isPresent());
        assertThat(result.get().getData().length, is(0));
    }

    // --- large content ---

    @Test
    void shouldHandleLargeData() {
        // 1MB payload
        byte[] largeData = new byte[1024 * 1024];
        Arrays.fill(largeData, (byte) 'x');
        store.put("large.bin", largeData, Collections.emptyMap());

        Optional<Blob> result = store.get("large.bin");
        assertTrue(result.isPresent());
        assertArrayEquals(largeData, result.get().getData());
    }

    // --- empty metadata ---

    @Test
    void shouldHandleNullMetadataGracefully() {
        // Blob constructor normalizes null metadata to emptyMap
        store.put("null-meta", "data".getBytes(), null);

        Optional<Blob> result = store.get("null-meta");
        assertTrue(result.isPresent());
        assertThat(result.get().getMetadata(), is(notNullValue()));
        assertThat(result.get().getMetadata().size(), is(0));
    }

    // --- binary data ---

    @Test
    void shouldPreserveBinaryDataExactly() {
        // All possible byte values (0x00 through 0xFF)
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }
        store.put("binary", binaryData, Collections.emptyMap());

        Optional<Blob> result = store.get("binary");
        assertTrue(result.isPresent());
        assertArrayEquals(binaryData, result.get().getData(),
            "binary data should survive round-trip without corruption");
    }
}
