package org.mockserver.collections;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A bounded {@link ConcurrentLinkedDeque} that evicts the oldest element(s) once it reaches
 * {@code maxSize}, invoking an optional callback on each evicted element.
 * <p>
 * <strong>Why the explicit size counter:</strong> {@link ConcurrentLinkedDeque#size()} is
 * documented as an <em>O(n)</em> operation (it walks the whole list). The eviction check runs
 * on every {@link #add}/{@link #offer}, so relying on {@code super.size()} made each insertion
 * O(n) once the deque was full — the hot path for MockServer's request/event log. Under a
 * sustained request load this manifested as CPU usage that climbed as the log filled and stayed
 * high (GitHub issue #2329). An {@link AtomicInteger} maintained by every mutating method makes
 * {@link #size()} and the eviction check O(1).
 * <p>
 * The counter is kept consistent by every size-changing method on this class
 * ({@link #add}, {@link #offer}, {@link #addAll} (via {@code add}), {@link #remove},
 * {@link #removeItem}, {@link #clear}, and the internal eviction). Callers must mutate the deque
 * only through these methods (MockServer's {@code MockServerEventLog} does); direct use of other
 * inherited bulk mutators is not supported by this subclass.
 *
 * @author jamesdbloom
 */
public class CircularConcurrentLinkedDeque<E> extends ConcurrentLinkedDeque<E> {

    private static final long serialVersionUID = 1L;

    private int maxSize;
    private final Consumer<E> onEvictCallback;
    // O(1) element count — see class javadoc. Updated by every mutating method below.
    private final AtomicInteger count = new AtomicInteger(0);

    public CircularConcurrentLinkedDeque(int maxSize, Consumer<E> onEvictCallback) {
        this.maxSize = maxSize;
        this.onEvictCallback = onEvictCallback;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * O(1) size, backed by an internal counter (unlike {@link ConcurrentLinkedDeque#size()}
     * which is O(n)).
     */
    @Override
    public int size() {
        return count.get();
    }

    @Override
    public boolean isEmpty() {
        return count.get() == 0;
    }

    @Override
    public boolean add(E element) {
        if (maxSize > 0) {
            evictExcessElements();
            if (super.add(element)) {
                count.incrementAndGet();
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    @Override
    public boolean addAll(Collection<? extends E> collection) {
        if (maxSize > 0) {
            boolean result = false;
            for (E element : collection) {
                if (add(element)) {
                    result = true;
                }
            }
            return result;
        } else {
            return false;
        }
    }

    @Override
    public boolean offer(E element) {
        if (maxSize > 0) {
            evictExcessElements();
            if (super.offer(element)) {
                count.incrementAndGet();
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    private void evictExcessElements() {
        while (count.get() >= maxSize) {
            E evicted = super.poll();
            if (evicted == null) {
                // deque already empty (defensive — should not happen while count >= maxSize > 0)
                break;
            }
            count.decrementAndGet();
            if (onEvictCallback != null) {
                onEvictCallback.accept(evicted);
            }
        }
    }

    @Override
    public void clear() {
        E evicted;
        while ((evicted = super.poll()) != null) {
            count.decrementAndGet();
            if (onEvictCallback != null) {
                onEvictCallback.accept(evicted);
            }
        }
    }

    /**
     * @deprecated use removeItem instead
     */
    @Deprecated
    @Override
    public boolean remove(Object o) {
        if (super.remove(o)) {
            count.decrementAndGet();
            return true;
        }
        return false;
    }

    public boolean removeItem(E e) {
        if (onEvictCallback != null) {
            onEvictCallback.accept(e);
        }
        if (super.remove(e)) {
            count.decrementAndGet();
            return true;
        }
        return false;
    }
}
