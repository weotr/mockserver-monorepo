package org.mockserver.blob.s3;

import org.junit.After;
import org.junit.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link S3BlobStoreRegistrar} registration mechanics.
 */
public class S3BlobStoreRegistrarTest {

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldRegisterS3BlobStoreFactory() {
        assertFalse("s3 should not be registered before register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));

        S3BlobStoreRegistrar.register();

        assertTrue("s3 should be registered after register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));
    }

    @Test
    public void shouldBeIdempotent() {
        S3BlobStoreRegistrar.register();
        S3BlobStoreRegistrar.register();

        assertTrue("s3 should still be registered after double register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));
    }
}
