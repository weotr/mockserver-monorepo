package org.mockserver.blob.s3;

import org.mockserver.configuration.Configuration;
import org.mockserver.state.StateBackendFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * Registers the {@link S3BlobStore} with the core
 * {@link StateBackendFactory} so that {@code blobStoreType=s3}
 * selects it. Call {@link #register()} at application startup, or
 * let the factory's auto-discovery mechanism load this class via
 * reflection when the module is on the classpath.
 * <p>
 * This design keeps {@code mockserver-core} free of any AWS SDK
 * dependency: the core factory uses a registry pattern, and this
 * module registers itself into it.
 */
public final class S3BlobStoreRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(S3BlobStoreRegistrar.class);

    private S3BlobStoreRegistrar() {
    }

    /**
     * Register the S3 blob store factory with {@link StateBackendFactory}.
     * Idempotent -- safe to call multiple times.
     */
    public static void register() {
        StateBackendFactory.registerBlobStoreFactory("s3", S3BlobStoreRegistrar::createS3BlobStore);
        LOG.info("registered S3BlobStore with StateBackendFactory");
    }

    /**
     * Creates an {@link S3BlobStore} from configuration properties.
     * <p>
     * Configuration:
     * <ul>
     *   <li>{@code blobStoreBucket} -- S3 bucket name (required)</li>
     *   <li>{@code blobStoreRegion} -- AWS region (default: us-east-1)</li>
     *   <li>{@code blobStoreEndpoint} -- endpoint override for S3-compatible
     *       stores like MinIO (optional)</li>
     *   <li>{@code blobStoreKeyPrefix} -- object key prefix (optional)</li>
     *   <li>{@code blobStoreAccessKeyId} + {@code blobStoreSecretAccessKey}
     *       -- explicit credentials (optional; falls back to default AWS
     *       credential chain)</li>
     * </ul>
     */
    static S3BlobStore createS3BlobStore(Configuration configuration) {
        String bucket = configuration.blobStoreBucket();
        if (bucket == null || bucket.isEmpty()) {
            throw new IllegalStateException(
                "blobStoreType=s3 requires blobStoreBucket to be configured");
        }

        String region = configuration.blobStoreRegion();
        if (region == null || region.isEmpty()) {
            region = "us-east-1";
        }

        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region));

        String endpoint = configuration.blobStoreEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint))
                .forcePathStyle(true);
        }

        String accessKeyId = configuration.blobStoreAccessKeyId();
        String secretAccessKey = configuration.blobStoreSecretAccessKey();
        if (accessKeyId != null && !accessKeyId.isEmpty()
            && secretAccessKey != null && !secretAccessKey.isEmpty()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        S3Client s3Client = builder.build();
        String keyPrefix = configuration.blobStoreKeyPrefix();

        return new S3BlobStore(s3Client, bucket, keyPrefix);
    }
}
