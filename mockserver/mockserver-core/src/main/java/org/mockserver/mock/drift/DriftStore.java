package org.mockserver.mock.drift;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe LRU-capped store of {@link DriftRecord} entries. Oldest entries
 * are evicted when the store reaches its maximum capacity.
 */
public class DriftStore {

    private static final DriftStore INSTANCE = new DriftStore(1000);

    private final int maxSize;
    private final Deque<DriftRecord> records = new ArrayDeque<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DriftStore(int maxSize) {
        this.maxSize = maxSize;
    }

    public static DriftStore getInstance() {
        return INSTANCE;
    }

    public void add(DriftRecord record) {
        lock.writeLock().lock();
        try {
            if (records.size() >= maxSize) {
                records.pollFirst();
            }
            records.addLast(record);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns up to {@code limit} most-recent records, newest first.
     */
    public List<DriftRecord> getRecent(int limit) {
        lock.readLock().lock();
        try {
            List<DriftRecord> result = new ArrayList<>();
            Iterator<DriftRecord> it = records.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                result.add(it.next());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all records for the given expectation ID, in insertion order.
     */
    public List<DriftRecord> getByExpectationId(String expectationId) {
        lock.readLock().lock();
        try {
            List<DriftRecord> result = new ArrayList<>();
            for (DriftRecord r : records) {
                if (expectationId.equals(r.getExpectationId())) {
                    result.add(r);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            records.clear();
        } finally {
            lock.writeLock().unlock();
        }
        PercentileTracker.getInstance().clear();
    }

    public int size() {
        lock.readLock().lock();
        try {
            return records.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
