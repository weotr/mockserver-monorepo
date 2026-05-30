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

    public List<V> toSortedList() {
        List<V> cached = sortedCache;
        if (cached == null) {
            cached = Collections.unmodifiableList(stream().collect(Collectors.toList()));
            sortedCache = cached;
        }
        return cached;
    }
}
