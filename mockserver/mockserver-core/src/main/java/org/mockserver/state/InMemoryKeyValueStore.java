package org.mockserver.state;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * General-purpose in-memory {@link KeyValueStore} backed by a
 * {@code ConcurrentHashMap}. Suitable for scenario states, CRUD entities,
 * and any unordered KV needs. NOT used for expectations (which need
 * {@link InMemoryExpectationKeyValueStore} for ordering/eviction).
 *
 * @param <V> the value type
 */
public class InMemoryKeyValueStore<V> implements KeyValueStore<V> {

    private final ConcurrentHashMap<String, VersionedEntry<V>> map = new ConcurrentHashMap<>();
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public Optional<Versioned<V>> get(String key) {
        VersionedEntry<V> entry = map.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new Versioned<>(entry.value, entry.version.get()));
    }

    @Override
    public long put(String key, V value) {
        VersionedEntry<V> existing = map.get(key);
        if (existing != null) {
            existing.value = value;
            long ver = existing.version.incrementAndGet();
            fireChanged(key);
            return ver;
        }
        VersionedEntry<V> newEntry = new VersionedEntry<>(value);
        VersionedEntry<V> prev = map.putIfAbsent(key, newEntry);
        if (prev != null) {
            // lost the race — update existing
            prev.value = value;
            long ver = prev.version.incrementAndGet();
            fireChanged(key);
            return ver;
        }
        fireChanged(key);
        return 1L;
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, V value) {
        VersionedEntry<V> entry = map.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entry.version.get() != expectedVersion) {
                return false;
            }
            entry.value = value;
            entry.version.incrementAndGet();
        }
        fireChanged(key);
        return true;
    }

    @Override
    public boolean compareAndRemove(String key, long expectedVersion) {
        VersionedEntry<V> entry = map.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entry.version.get() != expectedVersion) {
                return false;
            }
            map.remove(key);
        }
        fireChanged(key);
        return true;
    }

    @Override
    public boolean remove(String key) {
        boolean removed = map.remove(key) != null;
        if (removed) {
            fireChanged(key);
        }
        return removed;
    }

    @Override
    public Stream<Entry<V>> entries() {
        return map.entrySet().stream().map(e -> {
            VersionedEntry<V> ve = e.getValue();
            return new Entry<>(e.getKey(), new Versioned<>(ve.value, ve.version.get()));
        });
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void clear() {
        map.clear();
        fireCleared();
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
    }

    private void fireChanged(String key) {
        for (InvalidationListener listener : listeners) {
            listener.onChanged(key);
        }
    }

    private void fireCleared() {
        for (InvalidationListener listener : listeners) {
            listener.onCleared();
        }
    }

    private static final class VersionedEntry<V> {
        volatile V value;
        final AtomicLong version;

        VersionedEntry(V value) {
            this.value = value;
            this.version = new AtomicLong(1L);
        }
    }
}
