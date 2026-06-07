package org.mockserver.state.infinispan;

import java.io.Serializable;
import java.util.Objects;

/**
 * Wrapper that pairs a value with an explicit version number for storage in
 * an Infinispan cache. This enables optimistic-concurrency CAS via
 * {@code cache.replace(key, oldWrapper, newWrapper)} — Infinispan compares
 * the old value using {@link #equals(Object)}, which checks both the value's
 * identity AND the version, so a stale-version replace fails atomically.
 * <p>
 * Implements {@link Serializable} so it works with Infinispan's default
 * Java serialization marshaller. For phase 2c (clustering with ProtoStream),
 * a dedicated marshaller adapter can be added without changing this class.
 *
 * @param <V> the value type (must be serializable for Infinispan storage)
 */
public final class VersionedWrapper<V> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final V value;
    private final long version;

    public VersionedWrapper(V value, long version) {
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
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VersionedWrapper<?> that = (VersionedWrapper<?>) o;
        return version == that.version && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, version);
    }

    @Override
    public String toString() {
        return "VersionedWrapper{value=" + value + ", version=" + version + '}';
    }
}
