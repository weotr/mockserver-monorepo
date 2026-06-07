package org.mockserver.state;

import org.mockserver.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Pluggable factory for the {@link StateBackend}.
 * <p>
 * Mirrors the {@link org.mockserver.mock.ExpectationStoreFactory} pattern:
 * a volatile-registry with a default in-memory implementation. An optional
 * clustered backend (phase 2b, in a separate module) can register a
 * {@link Factory} that returns a clustering-aware {@code StateBackend}
 * without {@code mockserver-core} depending on the data grid.
 * <p>
 * Auto-discovery: when {@code stateBackend=infinispan} is configured and
 * no custom factory has been registered, the factory attempts to load
 * {@code InfinispanStateBackendRegistrar} via reflection from the classpath.
 * If the class is found, its {@code register()} method is called, which
 * registers the Infinispan factory. This keeps mockserver-core free of any
 * compile-time dependency on the Infinispan module.
 * <p>
 * Thread-safety: the factory reference is {@code volatile}; register/reset
 * are expected at startup.
 */
public final class StateBackendFactory {

    private static final Logger LOG = LoggerFactory.getLogger(StateBackendFactory.class);

    private static final String INFINISPAN_REGISTRAR_CLASS =
        "org.mockserver.state.infinispan.InfinispanStateBackendRegistrar";

    /**
     * Creates a {@link StateBackend} for an {@code HttpState} instance.
     */
    @FunctionalInterface
    public interface Factory {
        StateBackend create(Configuration configuration);
    }

    private static final Factory DEFAULT_FACTORY =
        configuration -> {
            BlobStore blobStore = createBlobStore(configuration);
            return new InMemoryStateBackend(configuration.maxExpectations(), blobStore);
        };

    private static volatile Factory factory = DEFAULT_FACTORY;

    private StateBackendFactory() {
    }

    /**
     * Register a custom factory (e.g. a clustered backend). Passing
     * {@code null} resets to the default in-memory factory.
     */
    public static void register(Factory customFactory) {
        factory = (customFactory != null) ? customFactory : DEFAULT_FACTORY;
    }

    /**
     * Reset to the default in-memory factory (primarily for tests).
     */
    public static void resetToDefault() {
        factory = DEFAULT_FACTORY;
    }

    /**
     * @return {@code true} if a non-default factory is registered.
     */
    public static boolean isCustomFactoryRegistered() {
        return factory != DEFAULT_FACTORY;
    }

    /**
     * Create the state backend via the registered factory. If
     * {@code stateBackend=infinispan} is configured and no custom factory
     * has been registered yet, attempts auto-discovery of the Infinispan
     * module from the classpath via reflection.
     */
    public static StateBackend create(Configuration configuration) {
        String backendName = configuration.stateBackend();
        if ("infinispan".equalsIgnoreCase(backendName) && !isCustomFactoryRegistered()) {
            discoverInfinispanBackend();
        }
        return factory.create(configuration);
    }

    /**
     * Creates the appropriate {@link BlobStore} based on the
     * {@code blobStoreType} configuration property.
     * <ul>
     *   <li>{@code "filesystem"} (default) — creates a {@link FilesystemBlobStore}
     *       that writes to the filesystem using the same I/O patterns as the
     *       pre-existing persistence classes, so on-disk behaviour is
     *       byte-for-byte identical.</li>
     *   <li>{@code "memory"} — creates an {@link InMemoryBlobStore} that holds
     *       blobs in-process only (lost on exit).</li>
     * </ul>
     */
    static BlobStore createBlobStore(Configuration configuration) {
        String blobStoreType = configuration.blobStoreType();
        if ("filesystem".equalsIgnoreCase(blobStoreType)) {
            return new FilesystemBlobStore(null);
        }
        return new InMemoryBlobStore();
    }

    /**
     * Attempt to load and register the Infinispan backend via reflection.
     * If the class is not on the classpath or registration fails, throws
     * {@link IllegalStateException} — silently falling through to in-memory
     * would cause split-brain: a node the operator believes is clustered
     * would run isolated in-memory.
     */
    private static synchronized void discoverInfinispanBackend() {
        if (isCustomFactoryRegistered()) {
            return; // another thread already registered
        }
        try {
            Class<?> registrarClass = Class.forName(INFINISPAN_REGISTRAR_CLASS);
            Method registerMethod = registrarClass.getMethod("register");
            registerMethod.invoke(null);
            LOG.info("auto-discovered Infinispan state backend from classpath");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "stateBackend=infinispan configured but " + INFINISPAN_REGISTRAR_CLASS
                    + " is not on the classpath; add the mockserver-state-infinispan dependency", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                "stateBackend=infinispan configured but failed to register the Infinispan state backend", e);
        }
    }
}
