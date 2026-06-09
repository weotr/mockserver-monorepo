package org.mockserver.blob.gcs;

import org.junit.After;
import org.junit.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GcsBlobStoreRegistrar} registration mechanics.
 */
public class GcsBlobStoreRegistrarTest {

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldRegisterGcsBlobStoreFactory() {
        assertFalse("gcs should not be registered before register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("gcs"));

        GcsBlobStoreRegistrar.register();

        assertTrue("gcs should be registered after register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("gcs"));
    }

    @Test
    public void shouldBeIdempotent() {
        GcsBlobStoreRegistrar.register();
        GcsBlobStoreRegistrar.register();

        assertTrue("gcs should still be registered after double register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("gcs"));
    }
}
