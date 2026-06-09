package org.mockserver.blob.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.mockserver.configuration.Configuration;
import org.mockserver.state.StateBackendFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the {@link AzureBlobStore} with the core
 * {@link StateBackendFactory} so that {@code blobStoreType=azure}
 * selects it. Call {@link #register()} at application startup, or
 * let the factory's auto-discovery mechanism load this class via
 * reflection when the module is on the classpath.
 * <p>
 * This design keeps {@code mockserver-core} free of any Azure SDK
 * dependency.
 */
public final class AzureBlobStoreRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(AzureBlobStoreRegistrar.class);

    private AzureBlobStoreRegistrar() {
    }

    /**
     * Register the Azure blob store factory with {@link StateBackendFactory}.
     * Idempotent -- safe to call multiple times.
     */
    public static void register() {
        StateBackendFactory.registerBlobStoreFactory("azure", AzureBlobStoreRegistrar::createAzureBlobStore);
        LOG.info("registered AzureBlobStore with StateBackendFactory");
    }

    /**
     * Creates an {@link AzureBlobStore} from configuration properties.
     * <p>
     * Configuration:
     * <ul>
     *   <li>{@code blobStoreContainer} -- Azure container name (required)</li>
     *   <li>{@code blobStoreConnectionString} -- Azure connection string
     *       (required; includes account name, key, and endpoint)</li>
     *   <li>{@code blobStoreKeyPrefix} -- blob name prefix (optional)</li>
     * </ul>
     */
    static AzureBlobStore createAzureBlobStore(Configuration configuration) {
        String container = configuration.blobStoreContainer();
        if (container == null || container.isEmpty()) {
            throw new IllegalStateException(
                "blobStoreType=azure requires blobStoreContainer to be configured");
        }

        String connectionString = configuration.blobStoreConnectionString();
        if (connectionString == null || connectionString.isEmpty()) {
            throw new IllegalStateException(
                "blobStoreType=azure requires blobStoreConnectionString to be configured");
        }

        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(container);
        String keyPrefix = configuration.blobStoreKeyPrefix();

        return new AzureBlobStore(containerClient, keyPrefix);
    }
}
