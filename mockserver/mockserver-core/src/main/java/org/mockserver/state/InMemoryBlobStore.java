package org.mockserver.state;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link BlobStore} implementation backed by a
 * {@code ConcurrentHashMap}. Blobs are held entirely in memory
 * and are lost on process exit.
 */
public class InMemoryBlobStore implements BlobStore {

    private final ConcurrentHashMap<String, Blob> blobs = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        blobs.put(key, new Blob(key, data, metadata));
    }

    @Override
    public Optional<Blob> get(String key) {
        return Optional.ofNullable(blobs.get(key));
    }

    @Override
    public List<String> list(String prefix) {
        return blobs.keySet().stream()
            .filter(k -> k.startsWith(prefix))
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String key) {
        return blobs.remove(key) != null;
    }
}
