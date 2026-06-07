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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * {@link KeyValueStore} backed by an Infinispan {@link Cache} in LOCAL
 * (non-clustered) mode. Versioning is managed explicitly via a
 * {@link VersionedWrapper} stored as the cache value, since LOCAL caches
 * do not support Infinispan's metadata versioning API.
 * <p>
 * CAS ({@link #compareAndSet}) uses {@code cache.replace(key, oldValue, newValue)}
 * which is atomic in Infinispan LOCAL mode, ensuring no lost updates.
 * <p>
 * Phase 2b: single-node only. Cluster pub/sub invalidation is deferred to
 * phase 2c (listeners are fired locally only).
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
    public boolean compareAndSet(String key, long expectedVersion, V value) {
        // Track whether the remapping lambda actually performed the CAS via
        // an AtomicBoolean. We cannot rely on the returned wrapper's version
        // alone because if the existing version already equals expectedVersion+1
        // (e.g. put→v1 then compareAndSet(expectedVersion=0)), the version
        // check would falsely report success without changing the value.
        // The AtomicBoolean is set to the final decision on every invocation
        // of the lambda (Infinispan may invoke it more than once in retries),
        // so no side-effects other than the boolean are performed inside.
        AtomicBoolean didCas = new AtomicBoolean(false);
        cache.compute(key, (k, existing) -> {
            if (existing == null || existing.getVersion() != expectedVersion) {
                didCas.set(false);
                return existing; // no change — CAS failed
            }
            didCas.set(true);
            return new VersionedWrapper<>(value, expectedVersion + 1);
        });
        boolean success = didCas.get();
        if (success) {
            fireChanged(key);
        }
        return success;
    }

    @Override
    public boolean compareAndRemove(String key, long expectedVersion) {
        // Track whether the remapping lambda actually performed the removal
        // via an AtomicBoolean — same pattern as compareAndSet. The entire
        // version check + removal decision is made inside the compute lambda
        // to avoid a TOCTOU race from a pre-compute get(). The AtomicBoolean
        // is set to the final decision on every lambda invocation (Infinispan
        // may retry), so no side-effects other than the boolean are performed.
        AtomicBoolean didRemove = new AtomicBoolean(false);
        cache.compute(key, (k, existing) -> {
            if (existing == null || existing.getVersion() != expectedVersion) {
                didRemove.set(false);
                return existing; // no change
            }
            didRemove.set(true);
            return null; // remove
        });
        boolean success = didRemove.get();
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
