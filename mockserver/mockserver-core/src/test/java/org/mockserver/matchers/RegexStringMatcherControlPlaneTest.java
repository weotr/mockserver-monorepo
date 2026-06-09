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
public class RegexStringMatcherControlPlaneTest {

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches((MatchDifference) null, NottableString.not("not_value")), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcher() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_value"), true).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcherAndNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_matcher"), true).matches((MatchDifference) null, NottableString.not("not_value")), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches((MatchDifference) null, NottableString.not("some_value")), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcher() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), true).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcherAndNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), true).matches((MatchDifference) null, NottableString.not("some_value")), is(true));
    }

    @Test
    public void shouldNotMatchMatchingString() {
        assertThat(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("some_value"), is(false));
    }

    @Test
    public void shouldMatchMatchingStringWithRegexSymbols() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), true).matches("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), is(true));
    }

    @Test
    public void shouldMatchMatchingRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{5}"), true).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string(null), true).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string(""), true).matches("some_value"), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches("not_matching"), is(false));
    }

    @Test
    public void shouldMatchIncorrectString() {
        assertThat(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("not_matching"), is(true));
    }

    @Test
    public void shouldNotMatchMatchingControlPlaneRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches("some_[a-z]{5}"), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectStringWithRegexSymbols() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), true).matches("text/html,application/xhtml+xml,application/xml;q=0.9;q=0.8"), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{4}"), true).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchNullTestForControlPlane() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches((MatchDifference) null, string(null)), is(false));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, string(null)), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTestForControlPlane() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches(""), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches(""), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectationAndTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), true).matches("/{{}"), is(false));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), true).matches("some_value"), is(false));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches("/{}"), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), true).matches("some_value"), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true).matches("/{}"), is(false));
    }
}
