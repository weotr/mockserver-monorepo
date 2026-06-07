package org.mockserver.state;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * An opaque binary blob with key and optional metadata, stored in a {@link BlobStore}.
 * Implements {@link Serializable} so it can be marshalled by Infinispan's
 * Java-serialization path in clustered mode.
 */
public final class Blob implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final byte[] data;
    private final Map<String, String> metadata;

    public Blob(String key, byte[] data, Map<String, String> metadata) {
        this.key = key;
        this.data = data;
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    public Blob(String key, byte[] data) {
        this(key, data, Collections.emptyMap());
    }

    public String getKey() {
        return key;
    }

    public byte[] getData() {
        return data;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
