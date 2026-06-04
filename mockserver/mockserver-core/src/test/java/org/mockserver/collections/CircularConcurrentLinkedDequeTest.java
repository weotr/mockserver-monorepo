package org.mockserver.collections;

import org.junit.Test;

import java.util.Arrays;

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

}