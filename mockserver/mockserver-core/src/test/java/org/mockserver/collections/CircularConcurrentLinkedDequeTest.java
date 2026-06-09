package org.mockserver.collections;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

import static org.hamcrest.core.Is.is;
/**
 * @author jamesdbloom
 */
public class CircularConcurrentLinkedDequeTest {

    @Test
    public void shouldNotAllowAddingMoreThenMaximumNumberOfEntriesWhenUsingAdd() {
        // given
        CircularConcurrentLinkedDeque<String> concurrentLinkedQueue = new CircularConcurrentLinkedDeque<String>(3, null);

        // when
        concurrentLinkedQueue.add("1");
        concurrentLinkedQueue.add("2");
        concurrentLinkedQueue.add("3");
        concurrentLinkedQueue.add("4");

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue, not(contains("1")));
        assertThat(concurrentLinkedQueue, contains("2", "3", "4"));
    }

    @Test
    public void shouldNotAllowAddingMoreThenMaximumNumberOfEntriesWhenUsingAddAll() {
        // given
        CircularConcurrentLinkedDeque<String> concurrentLinkedQueue = new CircularConcurrentLinkedDeque<String>(3, null);

        // when
        concurrentLinkedQueue.addAll(Arrays.asList("1", "2", "3", "4"));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue, not(contains("1")));
        assertThat(concurrentLinkedQueue, contains("2", "3", "4"));
    }

    @Test
    public void shouldInvokeEvictCallbackForOldestEntriesWhenFull() {
        // given
        List<String> evicted = new ArrayList<>();
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(2, evicted::add);

        // when
        queue.add("1");
        queue.add("2");
        queue.add("3");
        queue.add("4");

        // then
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("3", "4"));
        assertThat(evicted, contains("1", "2"));
    }

    @Test
    public void shouldKeepSizeConsistentAcrossRemoveItemAndClear() {
        // given
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(10, null);
        queue.add("1");
        queue.add("2");
        queue.add("3");

        // when / then — removeItem keeps size accurate
        assertThat(queue.size(), is(3));
        assertThat(queue.removeItem("2"), is(true));
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("1", "3"));

        // and clear resets size to zero
        queue.clear();
        assertThat(queue.size(), is(0));
        assertThat(queue.isEmpty(), is(true));
    }

    @Test
    public void shouldReturnFalseAndNotChangeSizeWhenRemovingMissingItem() {
        // given
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(10, null);
        queue.add("1");

        // when / then
        assertThat(queue.removeItem("missing"), is(false));
        assertThat(queue.size(), is(1));
    }

    @Test
    public void shouldReportZeroSizeAndRejectAddsWhenMaxSizeIsZero() {
        // given
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(0, null);

        // when / then
        assertThat(queue.add("1"), is(false));
        assertThat(queue.size(), is(0));
        assertThat(queue.isEmpty(), is(true));
    }

    /**
     * Regression guard for GitHub issue #2329 (CPU climbs as the request/event log fills).
     * The previous implementation called {@link java.util.concurrent.ConcurrentLinkedDeque#size()}
     * (O(n)) inside the per-insert eviction loop, so inserting at capacity performed ~n node
     * traversals per element and took minutes at this scale. With O(1) sizing it completes in
     * well under a second; the timeout is deliberately generous to avoid CI flakiness while still
     * failing fast if the O(n) behaviour ever returns.
     */
    @Test(timeout = 30000)
    public void shouldAddAtCapacityInConstantTimeRegardlessOfSize() {
        // given
        int maxSize = 50000;
        CircularConcurrentLinkedDeque<Integer> queue = new CircularConcurrentLinkedDeque<>(maxSize, null);

        // when — 150k inserts, 100k of them at capacity (each evicting the oldest)
        int operations = maxSize * 3;
        for (int i = 0; i < operations; i++) {
            queue.add(i);
        }

        // then
        assertThat(queue.size(), is(maxSize));
    }

}