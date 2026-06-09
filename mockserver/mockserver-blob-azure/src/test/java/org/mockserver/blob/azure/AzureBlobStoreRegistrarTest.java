package org.mockserver.blob.azure;

import org.junit.After;
import org.junit.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AzureBlobStoreRegistrar} registration mechanics.
 */
public class AzureBlobStoreRegistrarTest {

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldRegisterAzureBlobStoreFactory() {
        assertFalse("azure should not be registered before register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("azure"));

        AzureBlobStoreRegistrar.register();

        assertTrue("azure should be registered after register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("azure"));
    }

    @Test
    public void shouldBeIdempotent() {
        AzureBlobStoreRegistrar.register();
        AzureBlobStoreRegistrar.register();

        assertTrue("azure should still be registered after double register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("azure"));
    }
}
