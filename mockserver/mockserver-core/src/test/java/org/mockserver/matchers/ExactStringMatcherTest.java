package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class ExactStringMatcherTest {

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, "some_value"), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, "some_value"), is(true));

        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, NottableString.not("some_value")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, "some_value"), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, "some_value"), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, NottableString.not("some_value")), is(false));
    }

    @Test
    public void shouldMatchNotMatchingString() {
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, "some_other_value"), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, NottableString.not("some_other_value")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, NottableString.not("some_other_value")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, "some_other_value"), is(false));

        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, NottableString.not("some_other_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, "some_other_value"), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, "some_other_value"), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, NottableString.not("some_other_value")), is(true));
    }

    @Test
    public void shouldMatchNullMatcher() {
        assertThat(new ExactStringMatcher(new MockServerLogger(), string(null)).matches(null, "some_value"), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), string(null)).matches(null, "some_value"), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not(null)).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(null))).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(null))).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not(null))).matches(null, "some_value"), is(true));

        assertThat(new ExactStringMatcher(new MockServerLogger(), string(null)).matches(null, NottableString.not("some_value")), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), string(null)).matches(null, NottableString.not("some_value")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(null))).matches(null, "some_value"), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(null))).matches(null, "some_value"), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not(null)).matches(null, "some_value"), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not(null))).matches(null, NottableString.not("some_value")), is(false));
    }

    @Test
    public void shouldMatchNullMatched() {
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, (String) null), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, string(null)), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, NottableString.not(null)), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, NottableString.not(null)), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, (String) null), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, string(null)), is(false));

        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, NottableString.not(null)), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, (String) null), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, string(null)), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, (String) null), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, string(null)), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, NottableString.not(null)), is(true));
    }

    @Test
    public void shouldMatchEmptyMatcher() {
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("")).matches(null, "some_value"), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("")).matches(null, "some_value"), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("")).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(""))).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(""))).matches(null, NottableString.not("some_value")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not(""))).matches(null, "some_value"), is(true));

        assertThat(new ExactStringMatcher(new MockServerLogger(), string("")).matches(null, NottableString.not("some_value")), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("")).matches(null, NottableString.not("some_value")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(""))).matches(null, "some_value"), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string(""))).matches(null, "some_value"), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("")).matches(null, "some_value"), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not(""))).matches(null, NottableString.not("some_value")), is(false));
    }

    @Test
    public void shouldMatchEmptyMatched() {
        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, ""), is(false));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, NottableString.not("")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, NottableString.not("")), is(false));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, ""), is(false));

        assertThat(new ExactStringMatcher(new MockServerLogger(), string("some_value")).matches(null, NottableString.not("")), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), string("some_value"))).matches(null, ""), is(true));
        assertThat(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value")).matches(null, ""), is(true));
        assertThat(notMatcher(new ExactStringMatcher(new MockServerLogger(), NottableString.not("some_value"))).matches(null, NottableString.not("")), is(true));
    }
}
