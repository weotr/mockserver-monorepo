package org.mockserver.state.infinispan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the registrar correctly hooks the Infinispan backend
 * into the core {@link StateBackendFactory}.
 */
class InfinispanStateBackendRegistrarTest {

    @AfterEach
    void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    void shouldRegisterInfinispanFactory() {
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());

        InfinispanStateBackendRegistrar.register();

        assertTrue(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    void shouldDeregisterInfinispanFactory() {
        InfinispanStateBackendRegistrar.register();
        assertTrue(StateBackendFactory.isCustomFactoryRegistered());

        InfinispanStateBackendRegistrar.deregister();
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }
}
