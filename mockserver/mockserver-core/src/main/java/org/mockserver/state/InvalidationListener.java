package org.mockserver.state;

/**
 * Listener for state-change events on a {@link KeyValueStore}.
 * In the in-memory backend this is a no-op; in a clustered backend
 * it enables node-local cache invalidation on remote writes.
 */
public interface InvalidationListener {

    /**
     * Called when a single key has been created, updated, or removed.
     *
     * @param key the affected key
     */
    void onChanged(String key);

    /**
     * Called when the entire store has been cleared.
     */
    void onCleared();
}
