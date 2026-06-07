package org.mockserver.state.infinispan;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.mockserver.state.KeyValueStore;

/**
 * Runs the full {@link KeyValueStoreContract} against
 * {@link InfinispanKeyValueStore} backed by a LOCAL (non-clustered)
 * Infinispan cache. No JGroups network transport is started.
 */
public class InfinispanKeyValueStoreContractTest extends KeyValueStoreContract {

    private EmbeddedCacheManager cacheManager;

    @Override
    protected KeyValueStore<String> createStore() {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        global.nonClusteredDefault();
        global.serialization().allowList().addRegexp(".*");

        ConfigurationBuilder cacheConfig = new ConfigurationBuilder();
        cacheConfig.memory().storage(StorageType.HEAP);

        cacheManager = new DefaultCacheManager(global.build());
        cacheManager.defineConfiguration("test-store", cacheConfig.build());

        @SuppressWarnings("unchecked")
        Cache<String, VersionedWrapper<String>> cache =
            (Cache<String, VersionedWrapper<String>>) (Cache<?, ?>) cacheManager.getCache("test-store");
        return new InfinispanKeyValueStore<>(cache);
    }

    @Override
    protected void destroyStore() {
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }
}
