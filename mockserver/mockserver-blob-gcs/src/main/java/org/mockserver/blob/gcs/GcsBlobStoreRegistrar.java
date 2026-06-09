package org.mockserver.blob.gcs;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.mockserver.configuration.Configuration;
import org.mockserver.state.StateBackendFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the {@link GcsBlobStore} with the core
 * {@link StateBackendFactory} so that {@code blobStoreType=gcs}
 * selects it. Call {@link #register()} at application startup, or
 * let the factory's auto-discovery mechanism load this class via
 * reflection when the module is on the classpath.
 * <p>
 * This design keeps {@code mockserver-core} free of any Google
 * Cloud SDK dependency.
 */
public final class GcsBlobStoreRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(GcsBlobStoreRegistrar.class);

    private GcsBlobStoreRegistrar() {
    }

    /**
     * Register the GCS blob store factory with {@link StateBackendFactory}.
     * Idempotent -- safe to call multiple times.
     */
    public static void register() {
        StateBackendFactory.registerBlobStoreFactory("gcs", GcsBlobStoreRegistrar::createGcsBlobStore);
        LOG.info("registered GcsBlobStore with StateBackendFactory");
    }

    /**
     * Creates a {@link GcsBlobStore} from configuration properties.
     * <p>
     * Configuration:
     * <ul>
     *   <li>{@code blobStoreBucket} -- GCS bucket name (required)</li>
     *   <li>{@code blobStoreEndpoint} -- endpoint override for
     *       fake-gcs-server or other emulators (optional)</li>
     *   <li>{@code blobStoreKeyPrefix} -- object name prefix (optional)</li>
     *   <li>{@code blobStoreProjectId} -- GCS project ID (optional;
     *       falls back to application default credentials)</li>
     * </ul>
     */
    static GcsBlobStore createGcsBlobStore(Configuration configuration) {
        String bucket = configuration.blobStoreBucket();
        if (bucket == null || bucket.isEmpty()) {
            throw new IllegalStateException(
                "blobStoreType=gcs requires blobStoreBucket to be configured");
        }

        StorageOptions.Builder builder = StorageOptions.newBuilder();

        String projectId = configuration.blobStoreProjectId();
        if (projectId != null && !projectId.isEmpty()) {
            builder.setProjectId(projectId);
        }

        String endpoint = configuration.blobStoreEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.setHost(endpoint)
                .setCredentials(NoCredentials.getInstance())
                .setProjectId(projectId != null && !projectId.isEmpty() ? projectId : "test-project");
        }

        Storage storage = builder.build().getService();
        String keyPrefix = configuration.blobStoreKeyPrefix();

        return new GcsBlobStore(storage, bucket, keyPrefix);
    }
}
