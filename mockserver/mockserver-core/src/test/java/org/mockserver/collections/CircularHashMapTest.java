package org.mockserver.collections;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class CircularHashMapTest {

    @Test
    public void shouldNotContainMoreThenMaximumNumberOfEntries() {
        // given
        CircularHashMap<String, String> circularHashMap = new CircularHashMap<>(3);

        // when
        circularHashMap.put("1", "1");
        circularHashMap.put("2", "2");
        circularHashMap.put("3", "3");
        circularHashMap.put("4", "4");

        // then
        assertThat(circularHashMap.size(), is(3));
        assertThat(circularHashMap.containsKey("1"), is(false));
        assertThat(circularHashMap.containsKey("2"), is(true));
        assertThat(circularHashMap.containsKey("3"), is(true));
        assertThat(circularHashMap.containsKey("4"), is(true));
    }

    @Test
    public void shouldFindKeyByObject() {
        // given
        CircularHashMap<String, String> circularHashMap = new CircularHashMap<>(5);

        // when
        circularHashMap.put("0", "a");
        circularHashMap.put("1", "b");
        circularHashMap.put("2", "c");
        circularHashMap.put("3", "d");
        circularHashMap.put("4", "d");
        circularHashMap.put("5", "e");

        // then
        assertThat(circularHashMap.findKey("b"), is("1"));
        assertThat(circularHashMap.findKey("c"), is("2"));
        assertThat(circularHashMap.findKey("x"), nullValue());
        assertThat(circularHashMap.findKey("a"), nullValue());
        assertThat(circularHashMap.findKey("d"), is("3"));
    }

    @Test
    public void shouldCallEvictionListenerWhenEntryEvicted() {
        List<String> evicted = new ArrayList<>();
        CircularHashMap<String, String> circularHashMap = new CircularHashMap<>(2, evicted::add);

        circularHashMap.put("1", "a");
        circularHashMap.put("2", "b");
        assertThat(evicted.isEmpty(), is(true));

        circularHashMap.put("3", "c");
        assertThat(evicted.size(), is(1));
        assertThat(evicted.get(0), is("a"));

        circularHashMap.put("4", "d");
        assertThat(evicted.size(), is(2));
        assertThat(evicted.get(1), is("b"));
    }
}
