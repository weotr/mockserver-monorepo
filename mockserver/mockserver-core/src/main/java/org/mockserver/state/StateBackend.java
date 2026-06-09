package org.mockserver.state;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;

/**
 * Pluggable backend for all shared MockServer state. A single
 * {@code StateBackend} instance is created per {@code HttpState} via
 * {@link StateBackendFactory}.
 * <p>
 * The default {@link InMemoryStateBackend} wraps today's exact in-memory
 * data structures for zero behaviour change. A clustered implementation
 * (phase 2b+) can back these stores with a distributed cache.
 */
public interface StateBackend extends Closeable {

    /**
     * Returns the expectation key-value store. The in-memory implementation
     * internally wraps a {@code CircularPriorityQueue} for identical
     * ordering and eviction behaviour.
     */
    KeyValueStore<ExpectationEntry> expectations();

    /**
     * Returns the scenario-state key-value store. Keys are composite
     * scenario-name + isolation strings; values are state strings.
     */
    KeyValueStore<String> scenarioStates();

    /**
     * Returns a CRUD entity key-value store for the given namespace.
     * Each namespace corresponds to a distinct CRUD resource path.
     */
    KeyValueStore<ObjectNode> crudEntities(String namespace);

    /**
     * Returns the blob store for persisted expectations, recorded
     * cassettes, fixtures, and snapshots.
     */
    BlobStore blobs();

    /**
     * Adds an invalidation listener that receives change notifications
     * from ALL stores managed by this backend.
     */
    void addInvalidationListener(InvalidationListener listener);

    /**
     * Returns a unique identifier for this node/instance. Used to
     * distinguish local vs. remote writes in a clustered backend.
     */
    String nodeId();

    /**
     * Returns whether this backend is clustered (multi-node state
     * replication). When {@code true}, callers must use CAS on the
     * shared {@link KeyValueStore} for correctness-critical mutations
     * such as Times consumption. The default is {@code false} (single
     * node, all state node-local).
     */
    default boolean isClustered() {
        return false;
    }

    /**
     * Closes the backend and releases any resources. No-op for the
     * in-memory backend.
     */
    @Override
    void close();
}
