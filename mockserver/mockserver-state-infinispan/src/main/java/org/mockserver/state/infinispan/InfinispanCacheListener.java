package org.mockserver.state.infinispan;

import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.annotation.CacheEntriesEvicted;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryRemovedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntriesEvictedEvent;
import org.mockserver.state.InvalidationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Infinispan cache listener that bridges Infinispan cache events to the
 * MockServer {@link InvalidationListener} SPI. Registered on each cache
 * to enable cross-node invalidation in clustered mode.
 * <p>
 * In LOCAL (non-clustered) mode, cache events are NOT fired by Infinispan
 * for local writes (this is Infinispan's design), so the listener is
 * harmless but inactive — the local-write notification path in
 * {@link InfinispanKeyValueStore} handles local events directly.
 * <p>
 * In CLUSTERED mode, this listener fires for replicated writes from
 * remote nodes, enabling the node-local view rebuild in
 * {@code RequestMatchers.reconcileFromBackend()}.
 * <p>
 * The listener is annotated with {@code clustered = true} so it only
 * receives events for remote operations — local operations are handled
 * by the direct notification path in {@link InfinispanKeyValueStore}.
 */
@Listener(clustered = true)
public class InfinispanCacheListener {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanCacheListener.class);

    private final List<InvalidationListener> listeners;

    public InfinispanCacheListener(List<InvalidationListener> listeners) {
        this.listeners = listeners;
    }

    @CacheEntryCreated
    public void onCreated(CacheEntryCreatedEvent<String, ?> event) {
        if (!event.isPre()) {
            fireChanged(event.getKey());
        }
    }

    @CacheEntryModified
    public void onModified(CacheEntryModifiedEvent<String, ?> event) {
        if (!event.isPre()) {
            fireChanged(event.getKey());
        }
    }

    @CacheEntryRemoved
    public void onRemoved(CacheEntryRemovedEvent<String, ?> event) {
        if (!event.isPre()) {
            fireChanged(event.getKey());
        }
    }

    /**
     * Retained for forward-compatibility: Infinispan 14.x does not deliver
     * eviction events to clustered listeners ({@code @Listener(clustered=true)}),
     * so this handler is currently dead code. If a future Infinispan version
     * adds clustered eviction event delivery, this handler will activate
     * automatically without a code change.
     */
    @CacheEntriesEvicted
    public void onEvicted(CacheEntriesEvictedEvent<String, ?> event) {
        for (var entry : event.getEntries().entrySet()) {
            fireChanged(entry.getKey());
        }
    }

    private void fireChanged(String key) {
        for (InvalidationListener listener : listeners) {
            try {
                listener.onChanged(key);
            } catch (Exception e) {
                LOG.warn("cluster invalidation listener threw on onChanged({})", key, e);
            }
        }
    }
}
