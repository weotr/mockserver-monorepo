package org.mockserver.state;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Binary large-object store abstraction. Used for persisted expectations,
 * recorded cassettes, fixtures, and snapshots.
 * <p>
 * The in-memory implementation holds blobs in a {@code ConcurrentHashMap};
 * a filesystem implementation delegates to the existing file I/O paths;
 * cloud implementations (S3, GCS, Azure Blob) are SPI-only for now.
 * <p>
 * Extends {@link AutoCloseable} so that cloud implementations can release
 * their SDK client resources (connection pools, threads) on shutdown.
 * The default {@link #close()} is a no-op so in-memory and filesystem
 * implementations need no change.
 */
public interface BlobStore extends AutoCloseable {

    /**
     * Stores a blob, overwriting any existing blob with the same key.
     *
     * @param key      the blob key (e.g. a path-like string)
     * @param data     the binary data
     * @param metadata optional metadata (may be empty, must not be null)
     */
    void put(String key, byte[] data, Map<String, String> metadata);

    /**
     * Retrieves a blob by key.
     *
     * @param key the blob key
     * @return the blob, or empty if not present
     */
    Optional<Blob> get(String key);

    /**
     * Lists all blob keys that start with the given prefix.
     *
     * @param prefix the key prefix (e.g. "expectations/")
     * @return list of matching keys
     */
    List<String> list(String prefix);

    /**
     * Deletes a blob by key.
     *
     * @param key the blob key
     * @return true if the key was present
     */
    boolean delete(String key);

    /**
     * Releases any resources held by this blob store (e.g. cloud SDK
     * client connection pools). The default implementation is a no-op,
     * suitable for in-memory and filesystem stores.
     */
    @Override
    default void close() {
        // no-op by default
    }
}
