package org.mockserver.state.infinispan;

import org.infinispan.Cache;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.Versioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * {@link KeyValueStore} backed by an Infinispan {@link Cache}. Supports
 * both LOCAL (non-clustered) and REPL_SYNC (clustered) cache modes.
 * Versioning is managed explicitly via a {@link VersionedWrapper} stored
 * as the cache value.
 * <p>
 * CAS ({@link #compareAndSet}) uses {@code cache.replace(key, oldValue, newValue)}
 * which is atomic in both LOCAL and clustered modes. The previous
 * {@code cache.compute()}-based CAS approach was unsafe in REPL_SYNC mode
 * because Infinispan may re-execute the compute lambda on retry/conflict,
 * causing a side-channel AtomicBoolean to be overwritten by the retry
 * invocation.
 *
 * @param <V> the value type
 */
public class InfinispanKeyValueStore<V> implements KeyValueStore<V> {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanKeyValueStore.class);

    private final Cache<String, VersionedWrapper<V>> cache;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    public InfinispanKeyValueStore(Cache<String, VersionedWrapper<V>> cache) {
        this.cache = cache;
    }

    @Override
    public Optional<Versioned<V>> get(String key) {
        VersionedWrapper<V> wrapper = cache.get(key);
        if (wrapper == null) {
            return Optional.empty();
        }
        return Optional.of(new Versioned<>(wrapper.getValue(), wrapper.getVersion()));
    }

    @Override
    public long put(String key, V value) {
        // Use cache.compute() for atomic version-incrementing put. This
        // avoids the get-then-replace CAS loop which can fail in clustered
        // mode when equals() comparison on deserialized VersionedWrapper
        // instances is unreliable (e.g. when the value type does not
        // override equals). The return value of compute() is the new
        // wrapper, from which we read the assigned version.
        VersionedWrapper<V> result = cache.compute(key, (k, existing) -> {
            long version = (existing != null) ? existing.getVersion() + 1 : 1L;
            return new VersionedWrapper<>(value, version);
        });
        fireChanged(key);
        return result.getVersion();
    }

    @Override
    public Optional<Versioned<V>> putIfAbsent(String key, V value) {
        VersionedWrapper<V> newWrapper = new VersionedWrapper<>(value, 1L);
        VersionedWrapper<V> existing = cache.putIfAbsent(key, newWrapper);
        if (existing != null) {
            // Key already existed — return the existing value without modification
            return Optional.of(new Versioned<>(existing.getValue(), existing.getVersion()));
        }
        // Successfully created the entry
        fireChanged(key);
        return Optional.empty();
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, V value) {
        // Use Infinispan's native cache.replace(key, oldValue, newValue)
        // for atomic CAS. This avoids the re-execution problem with
        // cache.compute() in REPL_SYNC clustered mode: Infinispan may
        // re-execute the compute lambda on retry/conflict, and a
        // side-channel AtomicBoolean can be overwritten by the retry,
        // falsely reporting failure even though the CAS succeeded.
        //
        // cache.replace(K, V oldValue, V newValue) is a true CAS: it
        // atomically replaces the entry only if the current value
        // equals(oldValue). The VersionedWrapper.equals() method
        // checks both the version AND the value, so version-based CAS
        // works correctly as long as the old VersionedWrapper instance
        // matches what is in the cache.
        //
        // We first get() to obtain the current wrapper, verify its
        // version matches expectedVersion, then replace() with the
        // new wrapper. The replace() is atomic within Infinispan.
        VersionedWrapper<V> current = cache.get(key);
        if (current == null || current.getVersion() != expectedVersion) {
            return false;
        }
        VersionedWrapper<V> updated = new VersionedWrapper<>(value, expectedVersion + 1);
        boolean success = cache.replace(key, current, updated);
        if (success) {
            fireChanged(key);
        }
        return success;
    }

    @Override
    public boolean compareAndRemove(String key, long expectedVersion) {
        // Use get-then-conditional-remove to avoid the compute lambda
        // re-execution issue in REPL_SYNC clustered mode (see compareAndSet).
        VersionedWrapper<V> current = cache.get(key);
        if (current == null || current.getVersion() != expectedVersion) {
            return false;
        }
        boolean success = cache.remove(key, current);
        if (success) {
            fireChanged(key);
        }
        return success;
    }

    @Override
    public boolean remove(String key) {
        boolean removed = cache.remove(key) != null;
        if (removed) {
            fireChanged(key);
        }
        return removed;
    }

    @Override
    public Stream<Entry<V>> entries() {
        return cache.entrySet().stream().map(e -> {
            VersionedWrapper<V> w = e.getValue();
            return new Entry<>(e.getKey(), new Versioned<>(w.getValue(), w.getVersion()));
        });
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public void clear() {
        cache.clear();
        fireCleared();
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
    }

    private void fireChanged(String key) {
        for (InvalidationListener listener : listeners) {
            try {
                listener.onChanged(key);
            } catch (Exception e) {
                LOG.warn("invalidation listener threw on onChanged({})", key, e);
            }
        }
    }

    private void fireCleared() {
        for (InvalidationListener listener : listeners) {
            try {
                listener.onCleared();
            } catch (Exception e) {
                LOG.warn("invalidation listener threw on onCleared()", e);
            }
        }
    }
}
