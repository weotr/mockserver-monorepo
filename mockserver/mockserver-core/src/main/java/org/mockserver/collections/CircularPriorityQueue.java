package org.mockserver.collections;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author jamesdbloom
 */
public class CircularPriorityQueue<K, V, SLK extends Keyed<K>> {
    private int maxSize;
    private final Function<V, SLK> skipListKeyFunction;
    private final Function<V, K> mapKeyFunction;
    private final ConcurrentSkipListSet<SLK> sortOrderSkipList;
    private final ConcurrentLinkedQueue<V> insertionOrderQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<K, V> byKey = new ConcurrentHashMap<>();
    // Cached snapshot of the sorted list; nulled on every mutation so toSortedList()
    // rebuilds lazily. volatile ensures the null write is visible to all threads
    // immediately (no stale-cache reads after an add/remove).
    private volatile List<V> sortedCache = null;

    public CircularPriorityQueue(int maxSize, Comparator<? super SLK> skipListComparator, Function<V, SLK> skipListKeyFunction, Function<V, K> mapKeyFunction) {
        sortOrderSkipList = new ConcurrentSkipListSet<>(skipListComparator);
        this.maxSize = maxSize;
        this.skipListKeyFunction = skipListKeyFunction;
        this.mapKeyFunction = mapKeyFunction;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        sortedCache = null;
    }

    public void removePriorityKey(V element) {
        sortOrderSkipList.remove(skipListKeyFunction.apply(element));
        sortedCache = null;
    }

    public void addPriorityKey(V element) {
        sortOrderSkipList.add(skipListKeyFunction.apply(element));
        sortedCache = null;
    }

    public void add(V element) {
        if (maxSize > 0 && element != null) {
            insertionOrderQueue.offer(element);
            sortOrderSkipList.add(skipListKeyFunction.apply(element));
            byKey.put(mapKeyFunction.apply(element), element);
            while (insertionOrderQueue.size() > maxSize) {
                V elementToRemove = insertionOrderQueue.poll();
                sortOrderSkipList.remove(skipListKeyFunction.apply(elementToRemove));
                byKey.remove(mapKeyFunction.apply(elementToRemove));
            }
            sortedCache = null;
        }
    }

    /**
     * Replaces the value associated with the given key in place, preserving
     * the element's position in {@code insertionOrderQueue} (and therefore
     * its eviction order). The old value is swapped out of the insertion
     * queue and byKey map and the new value takes its slot. Priority-sort
     * keys are updated (old removed, new added).
     *
     * @param key      the key that identifies the existing element
     * @param newValue the replacement value
     * @return {@code true} if the key was found and the value replaced
     */
    public boolean replaceValue(K key, V newValue) {
        V existing = byKey.get(key);
        if (existing == null) {
            return false;
        }
        // Swap in the insertion-order queue: replace the old element with
        // the new one at the same logical position.  ConcurrentLinkedQueue
        // does not offer an index-based replace, so we copy into a list,
        // swap, and rebuild.  This is O(n) but executions are serialized
        // by the caller (single-writer contract) and n == maxExpectations
        // which is typically small (hundreds).
        List<V> snapshot = new ArrayList<>(insertionOrderQueue);
        int idx = snapshot.indexOf(existing);
        if (idx < 0) {
            // Element not in insertion queue — shouldn't happen, but fall
            // back to a safe add-at-end to avoid data loss.
            insertionOrderQueue.offer(newValue);
        } else {
            snapshot.set(idx, newValue);
            insertionOrderQueue.clear();
            for (V v : snapshot) {
                insertionOrderQueue.offer(v);
            }
        }
        // Update byKey
        byKey.put(key, newValue);
        // Update priority sort: remove old, add new
        sortOrderSkipList.remove(skipListKeyFunction.apply(existing));
        sortOrderSkipList.add(skipListKeyFunction.apply(newValue));
        sortedCache = null;
        return true;
    }

    public boolean remove(V element) {
        if (element != null) {
            insertionOrderQueue.remove(element);
            byKey.remove(mapKeyFunction.apply(element));
            boolean removed = sortOrderSkipList.remove(skipListKeyFunction.apply(element));
            sortedCache = null;
            return removed;
        } else {
            return false;
        }
    }

    public int size() {
        return insertionOrderQueue.size();
    }

    public Stream<V> stream() {
        return sortOrderSkipList.stream().map(item -> byKey.get(item.getKey())).filter(Objects::nonNull);
    }

    public Optional<V> getByKey(K key) {
        if (key != null && !"".equals(key)) {
            return Optional.ofNullable(byKey.get(key));
        } else {
            return Optional.empty();
        }
    }

    public Map<K, V> keyMap() {
        return new HashMap<>(byKey);
    }

    public boolean isEmpty() {
        return insertionOrderQueue.isEmpty();
    }

    /**
     * Returns a cached, unmodifiable sorted snapshot of this queue's elements.
     * The snapshot is rebuilt lazily when any mutation nulls the cache.
     * <p>
     * <b>Eventually-consistent under concurrent mutation:</b> a call to
     * this method concurrent with a control-plane mutation (add/remove/
     * reconcileFromBackend) may return a snapshot that does not yet reflect
     * the in-flight mutation. This is the existing control-plane / data-plane
     * concurrency contract — no lock is held on the matching hot path.
     */
    public List<V> toSortedList() {
        List<V> cached = sortedCache;
        if (cached == null) {
            cached = Collections.unmodifiableList(stream().collect(Collectors.toList()));
            sortedCache = cached;
        }
        return cached;
    }
}
