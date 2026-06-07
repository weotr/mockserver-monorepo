package org.mockserver.state;

/**
 * A value paired with an optimistic-concurrency version number.
 * The version is incremented on every mutation; {@link KeyValueStore#compareAndSet}
 * uses it to detect concurrent updates.
 *
 * @param <V> the value type
 */
public final class Versioned<V> {

    private final V value;
    private final long version;

    public Versioned(V value, long version) {
        this.value = value;
        this.version = version;
    }

    public V getValue() {
        return value;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "Versioned{value=" + value + ", version=" + version + '}';
    }
}
