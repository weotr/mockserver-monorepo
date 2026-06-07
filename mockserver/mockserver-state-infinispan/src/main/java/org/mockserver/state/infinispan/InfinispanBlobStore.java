package org.mockserver.state.infinispan;

import org.infinispan.Cache;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link BlobStore} backed by an Infinispan LOCAL cache. Blobs are stored
 * as serialized {@link Blob} instances keyed by their path-like key.
 * <p>
 * Phase 2b: single-node only, no clustering.
 */
public class InfinispanBlobStore implements BlobStore {

    private final Cache<String, Blob> cache;

    public InfinispanBlobStore(Cache<String, Blob> cache) {
        this.cache = cache;
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        cache.put(key, new Blob(key, data, metadata));
    }

    @Override
    public Optional<Blob> get(String key) {
        return Optional.ofNullable(cache.get(key));
    }

    @Override
    public List<String> list(String prefix) {
        return cache.keySet().stream()
            .filter(k -> k.startsWith(prefix))
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String key) {
        return cache.remove(key) != null;
    }
}
