package org.mockserver.state.infinispan;

import org.mockserver.state.StateBackendFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the {@link InfinispanStateBackend} with the core
 * {@link StateBackendFactory} so that {@code stateBackend=infinispan}
 * selects it. Call {@link #register()} at application startup (or
 * use the static initializer block, which fires when this class is
 * loaded — the class is loaded when it appears on the classpath and
 * the factory's {@code create()} method resolves the backend name).
 * <p>
 * This design keeps {@code mockserver-core} free of any Infinispan
 * dependency: the core factory uses a volatile-registry pattern, and
 * this module registers itself into it.
 * <p>
 * Phase 2c: the factory now passes the full {@link org.mockserver.configuration.Configuration}
 * to {@link InfinispanStateBackend} so it can inspect clustering config
 * (clusterEnabled, clusterName, clusterTransportConfig).
 */
public final class InfinispanStateBackendRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanStateBackendRegistrar.class);

    private InfinispanStateBackendRegistrar() {
    }

    /**
     * Register the Infinispan factory with {@link StateBackendFactory}.
     * Idempotent — safe to call multiple times.
     */
    public static void register() {
        StateBackendFactory.register(
            configuration -> new InfinispanStateBackend(configuration)
        );
        LOG.info("registered InfinispanStateBackend with StateBackendFactory");
    }

    /**
     * Deregister the Infinispan factory, reverting to the default
     * in-memory backend (primarily for tests).
     */
    public static void deregister() {
        StateBackendFactory.resetToDefault();
    }
}
