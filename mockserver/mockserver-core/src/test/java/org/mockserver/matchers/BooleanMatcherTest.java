package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class BooleanMatcherTest {

    @Test
    public void shouldMatchMatchingExpectations() {
        assertThat(new BooleanMatcher(new MockServerLogger(), true).matches(null, true), is(true));
        assertThat(new BooleanMatcher(new MockServerLogger(), false).matches(null, false), is(true));
    }

    @Test
    public void shouldMatchNullExpectations() {
        assertThat(new BooleanMatcher(new MockServerLogger(), null).matches(null, null), is(true));
        assertThat(new BooleanMatcher(new MockServerLogger(), null).matches(null, false), is(true));
    }

    @Test
    public void shouldNotMatchNonMatchingExpectations() {
        assertThat(new BooleanMatcher(new MockServerLogger(), true).matches(null, false), is(false));
        assertThat(new BooleanMatcher(new MockServerLogger(), false).matches(null, true), is(false));
    }

    @Test
    public void shouldNotMatchNullAgainstNonMatchingExpectations() {
        assertThat(new BooleanMatcher(new MockServerLogger(), true).matches(null, null), is(false));
        assertThat(new BooleanMatcher(new MockServerLogger(), false).matches(null, null), is(false));
    }

}
