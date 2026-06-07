package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.mockserver.configuration.Configuration;
import org.mockserver.state.*;
import org.mockserver.uuid.UUIDService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Infinispan-backed {@link StateBackend} supporting both LOCAL (non-clustered)
 * and CLUSTERED (JGroups transport, REPL_SYNC) modes.
 * <p>
 * <b>LOCAL mode</b> (default, {@code clusterEnabled=false}): single-node,
 * no JGroups network transport, identical behaviour to Phase 2b. The Java
 * serialization allow-list remains permissive ({@code ".*"}) because caches
 * are heap-only with no network marshalling exposure.
 * <p>
 * <b>CLUSTERED mode</b> ({@code clusterEnabled=true}): starts a JGroups
 * transport for multi-node state replication. Caches use REPL_SYNC so all
 * writes are synchronously replicated to all cluster members. The Java
 * serialization allow-list is <b>tightened</b> to an explicit set of
 * packages covering exactly the types that cross the wire:
 * <ul>
 *   <li>{@code org.mockserver.state.infinispan.*} (VersionedWrapper)</li>
 *   <li>{@code org.mockserver.state.*} (ExpectationEntry)</li>
 *   <li>{@code org.mockserver.mock.*} (Expectation, domain model)</li>
 *   <li>{@code org.mockserver.model.*} (HttpRequest, HttpResponse, etc.)</li>
 *   <li>{@code org.mockserver.matchers.*} (TimeToLive, Times)</li>
 *   <li>{@code com.fasterxml.jackson.*} (ObjectNode for CRUD entities)</li>
 *   <li>{@code java.lang.*}, {@code java.util.*}, {@code java.time.*}
 *       (primitives/wrappers, collections, time types)</li>
 * </ul>
 * This explicit allow-list resolves the P0 security gate from Phase 2b —
 * deserialization gadget chains from untrusted packages are blocked.
 * <p>
 * Cluster invalidation: in CLUSTERED mode, Infinispan {@code @Listener}
 * annotated listeners are attached to each cache. When a remote write
 * arrives (replication from another node), the listener fires
 * {@link InvalidationListener#onChanged(String)} or
 * {@link InvalidationListener#onCleared()}, which triggers the node-local
 * view rebuild in {@code RequestMatchers.reconcileFromBackend()}.
 */
public class InfinispanStateBackend implements StateBackend {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanStateBackend.class);

    static final String EXPECTATIONS_CACHE = "expectations";
    static final String SCENARIO_STATES_CACHE = "scenarioStates";
    static final String BLOBS_CACHE = "blobs";
    static final String CRUD_CACHE_PREFIX = "crud-";

    private final EmbeddedCacheManager cacheManager;
    private final InfinispanKeyValueStore<ExpectationEntry> expectations;
    private final InfinispanKeyValueStore<String> scenarioStates;
    private final ConcurrentHashMap<String, KeyValueStore<ObjectNode>> crudStores;
    private final InfinispanBlobStore blobStore;
    private final String nodeId;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();
    private final boolean clustered;
    // Set of cache names for which a clustered Infinispan @Listener has
    // already been registered. Prevents N^2 listener firing when
    // addInvalidationListener is called N times.
    private final java.util.Set<String> registeredClusterListenerCaches = ConcurrentHashMap.newKeySet();

    /**
     * Creates an Infinispan state backend in the appropriate mode based
     * on configuration. When {@code configuration.clusterEnabled()} is
     * {@code true}, a JGroups-transported clustered cache manager is
     * created; otherwise a LOCAL (non-clustered) manager is used.
     *
     * @param configuration the MockServer configuration
     */
    public InfinispanStateBackend(Configuration configuration) {
        this.nodeId = UUIDService.getUUID();
        this.clustered = configuration.clusterEnabled();
        this.cacheManager = clustered
            ? createClusteredCacheManager(configuration)
            : createLocalCacheManager(configuration.maxExpectations());

        this.expectations = createKeyValueStore(EXPECTATIONS_CACHE);
        this.scenarioStates = createKeyValueStore(SCENARIO_STATES_CACHE);
        this.blobStore = new InfinispanBlobStore(cacheManager.getCache(BLOBS_CACHE));
        this.crudStores = new ConcurrentHashMap<>();

        LOG.info("InfinispanStateBackend started (mode={}, nodeId={}, clusterName={})",
            clustered ? "CLUSTERED" : "LOCAL",
            nodeId,
            clustered ? configuration.clusterName() : "n/a");
    }

    /**
     * Backward-compatible constructor for tests and single-node usage.
     * Creates a LOCAL (non-clustered) backend.
     *
     * @param maxExpectations the maximum number of expectations
     */
    public InfinispanStateBackend(int maxExpectations) {
        this.nodeId = UUIDService.getUUID();
        this.clustered = false;
        this.cacheManager = createLocalCacheManager(maxExpectations);
        this.expectations = createKeyValueStore(EXPECTATIONS_CACHE);
        this.scenarioStates = createKeyValueStore(SCENARIO_STATES_CACHE);
        this.blobStore = new InfinispanBlobStore(cacheManager.getCache(BLOBS_CACHE));
        this.crudStores = new ConcurrentHashMap<>();

        LOG.info("InfinispanStateBackend started (LOCAL mode, nodeId={})", nodeId);
    }

    // --- Cache Manager creation ---

    /**
     * Creates a LOCAL (non-clustered) cache manager — Phase 2b behaviour,
     * byte-for-byte identical.
     */
    private static EmbeddedCacheManager createLocalCacheManager(int maxExpectations) {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        global.nonClusteredDefault();
        // LOCAL mode: heap-only, no network marshalling. Allow-list is
        // permissive because no untrusted bytes are ever deserialized.
        global.serialization().allowList().addRegexp(".*");

        ConfigurationBuilder expectationsConfig = new ConfigurationBuilder();
        expectationsConfig
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(maxExpectations)
                .whenFull(EvictionStrategy.REMOVE);

        ConfigurationBuilder unboundedConfig = new ConfigurationBuilder();
        unboundedConfig.memory().storage(StorageType.HEAP);

        DefaultCacheManager manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(EXPECTATIONS_CACHE, expectationsConfig.build());
        manager.defineConfiguration(SCENARIO_STATES_CACHE, unboundedConfig.build());
        manager.defineConfiguration(BLOBS_CACHE, unboundedConfig.build());

        return manager;
    }

    /**
     * Creates a CLUSTERED cache manager with JGroups transport and REPL_SYNC
     * caches. The serialization allow-list is restricted to the exact
     * packages that cross the wire (P0 security gate resolved).
     */
    private static EmbeddedCacheManager createClusteredCacheManager(Configuration configuration) {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();

        // JGroups transport
        String transportConfig = configuration.clusterTransportConfig();
        if (transportConfig != null && !transportConfig.isEmpty()) {
            global.transport().defaultTransport()
                .clusterName(configuration.clusterName())
                .addProperty("configurationFile", transportConfig);
        } else {
            // Use the built-in loopback JGroups stack for embedded testing
            global.transport().defaultTransport()
                .clusterName(configuration.clusterName())
                .addProperty("configurationFile", "jgroups-loopback.xml");
        }

        // Use Java serialization for clustered wire format (not ProtoStream)
        // so that VersionedWrapper<V> and its payloads (ExpectationEntry,
        // String, ObjectNode, Blob) can be marshalled without per-type
        // proto schema definitions. The domain model (Expectation) is
        // serialized as JSON inside ExpectationEntry's custom writeObject.
        global.serialization().marshaller(new JavaSerializationMarshaller());

        // P0 SECURITY GATE: explicit allow-list covering exactly the types
        // that Infinispan needs to serialize for clustered replication.
        // NO wildcard. Each pattern covers a specific MockServer package or
        // a JDK/library type used as a field within the serialized objects.
        global.serialization().allowList()
            .addRegexp("org\\.mockserver\\.state\\.infinispan\\..*")   // VersionedWrapper
            .addRegexp("org\\.mockserver\\.state\\..*")                // ExpectationEntry, Blob
            .addRegexp("org\\.mockserver\\.mock\\..*")                 // Expectation
            .addRegexp("org\\.mockserver\\.model\\..*")                // HttpRequest, HttpResponse, etc.
            .addRegexp("org\\.mockserver\\.matchers\\..*")             // TimeToLive, Times
            .addRegexp("com\\.fasterxml\\.jackson\\..*")               // ObjectNode (CRUD entities)
            .addRegexp("java\\.lang\\..*")                             // String, Long, etc.
            .addRegexp("java\\.util\\..*")                             // Collections, Map entries
            .addRegexp("java\\.time\\..*")                             // Instant, Duration, etc.
            .addRegexp("\\[B");                                        // byte[] for Blob data

        // Expectations cache: REPL_SYNC, bounded
        ConfigurationBuilder expectationsConfig = new ConfigurationBuilder();
        expectationsConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(configuration.maxExpectations())
                .whenFull(EvictionStrategy.REMOVE);

        // Scenario states cache: REPL_SYNC, unbounded
        ConfigurationBuilder scenarioConfig = new ConfigurationBuilder();
        scenarioConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory().storage(StorageType.HEAP);

        // Blobs cache: REPL_SYNC, unbounded
        ConfigurationBuilder blobsConfig = new ConfigurationBuilder();
        blobsConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory().storage(StorageType.HEAP);

        DefaultCacheManager manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(EXPECTATIONS_CACHE, expectationsConfig.build());
        manager.defineConfiguration(SCENARIO_STATES_CACHE, scenarioConfig.build());
        manager.defineConfiguration(BLOBS_CACHE, blobsConfig.build());

        return manager;
    }

    @SuppressWarnings("unchecked")
    private <V> InfinispanKeyValueStore<V> createKeyValueStore(String cacheName) {
        Cache<String, VersionedWrapper<V>> cache =
            (Cache<String, VersionedWrapper<V>>) (Cache<?, ?>) cacheManager.getCache(cacheName);
        return new InfinispanKeyValueStore<>(cache);
    }

    // --- StateBackend implementation ---

    @Override
    public KeyValueStore<ExpectationEntry> expectations() {
        return expectations;
    }

    @Override
    public KeyValueStore<String> scenarioStates() {
        return scenarioStates;
    }

    @Override
    public KeyValueStore<ObjectNode> crudEntities(String namespace) {
        return crudStores.computeIfAbsent(namespace, ns -> {
            String cacheName = CRUD_CACHE_PREFIX + ns;
            ConfigurationBuilder crudConfig = new ConfigurationBuilder();
            if (clustered) {
                crudConfig.clustering().cacheMode(CacheMode.REPL_SYNC);
            }
            crudConfig.memory().storage(StorageType.HEAP);
            cacheManager.defineConfiguration(cacheName, crudConfig.build());
            return createKeyValueStore(cacheName);
        });
    }

    @Override
    public BlobStore blobs() {
        return blobStore;
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
        // Local invalidation (fires for local puts in InfinispanKeyValueStore)
        expectations.addInvalidationListener(listener);
        scenarioStates.addInvalidationListener(listener);
        // Clustered invalidation (fires for REMOTE writes only, via
        // Infinispan's @Listener(clustered=true)). Register the cluster
        // listener ONCE per cache — it bridges to the shared `listeners`
        // list, so subsequently added InvalidationListeners are picked up
        // automatically. In LOCAL mode these listeners never fire because
        // there are no remote events.
        if (clustered) {
            registerClusterListenerOnce(EXPECTATIONS_CACHE);
            registerClusterListenerOnce(SCENARIO_STATES_CACHE);
        }
    }

    /**
     * Registers an Infinispan clustered cache listener on the named cache
     * exactly ONCE. The listener bridges remote cache events to the shared
     * {@code listeners} list, so all current and future InvalidationListeners
     * are notified. Subsequent calls for the same cache name are no-ops.
     */
    private void registerClusterListenerOnce(String cacheName) {
        if (registeredClusterListenerCaches.add(cacheName)) {
            Cache<?, ?> cache = cacheManager.getCache(cacheName);
            InfinispanCacheListener cacheListener = new InfinispanCacheListener(listeners);
            cache.addListener(cacheListener);
        }
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    /**
     * Returns whether this backend is in clustered mode.
     */
    public boolean isClustered() {
        return clustered;
    }

    /**
     * Returns the underlying cache manager (for tests and diagnostics).
     */
    EmbeddedCacheManager getCacheManager() {
        return cacheManager;
    }

    @Override
    public void close() {
        LOG.info("stopping InfinispanStateBackend (mode={}, nodeId={})",
            clustered ? "CLUSTERED" : "LOCAL", nodeId);
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }
}
