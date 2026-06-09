package org.mockserver.state;

import org.mockserver.configuration.Configuration;

/**
 * SPI for pluggable {@link BlobStore} implementations. Cloud blob store
 * modules (S3, GCS, Azure) register a factory via
 * {@link StateBackendFactory#registerBlobStoreFactory(String, BlobStoreFactory)}
 * without requiring mockserver-core to depend on any cloud SDK.
 * <p>
 * The factory is invoked by {@link StateBackendFactory#createBlobStore(Configuration)}
 * when the {@code blobStoreType} configuration property matches the registered
 * type name.
 */
@FunctionalInterface
public interface BlobStoreFactory {

    /**
     * Creates a {@link BlobStore} from the given configuration.
     *
     * @param configuration the MockServer configuration
     * @return a configured blob store instance
     */
    BlobStore create(Configuration configuration);
}
