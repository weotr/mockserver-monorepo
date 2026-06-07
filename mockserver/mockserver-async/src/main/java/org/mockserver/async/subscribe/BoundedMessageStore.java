package org.mockserver.async.subscribe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A thread-safe, bounded FIFO store for {@link RecordedMessage} instances.
 * When the store reaches its capacity, the oldest message is evicted to make room.
 * <p>
 * Uses a synchronized {@link ArrayDeque} internally — efficient O(1) add/evict,
 * and safe for concurrent producer (broker thread) + reader (control-plane HTTP thread)
 * patterns with a lock per-channel (not global).
 */
public class BoundedMessageStore {

    /**
     * Default maximum number of recorded messages per channel.
     */
    public static final int DEFAULT_MAX_RECORDED_MESSAGES = 1000;

    private final ArrayDeque<RecordedMessage> deque;
    private final int maxSize;

    public BoundedMessageStore() {
        this(DEFAULT_MAX_RECORDED_MESSAGES);
    }

    public BoundedMessageStore(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
        }
        this.maxSize = maxSize;
        this.deque = new ArrayDeque<>(Math.min(maxSize, 64));
    }

    /**
     * Add a message to the store. If the store is at capacity, the oldest message
     * is evicted (FIFO). Thread-safe.
     */
    public synchronized void add(RecordedMessage message) {
        if (deque.size() >= maxSize) {
            deque.pollFirst();
        }
        deque.addLast(message);
    }

    /**
     * Return a snapshot copy of all messages currently in the store.
     * The returned list is independent of the store (safe to iterate without locking).
     */
    public synchronized List<RecordedMessage> snapshot() {
        return new ArrayList<>(deque);
    }

    /**
     * Return the current number of messages in the store.
     */
    public synchronized int size() {
        return deque.size();
    }

    /**
     * Clear all messages.
     */
    public synchronized void clear() {
        deque.clear();
    }
}
