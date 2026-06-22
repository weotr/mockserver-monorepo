package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.matchers.NotMatcher.notMatcher;

/**
 * @author jamesdbloom
 */
public class FuzzyMatcherTest {

    private FuzzyMatcher matcher(String value, double threshold, boolean ignoreCase) {
        return new FuzzyMatcher(new MockServerLogger(), value, threshold, ignoreCase);
    }

    @Test
    public void shouldMatchIdenticalString() {
        assertThat(matcher("some_value", 0.8d, false).matches(null, "some_value"), is(true));
    }

    @Test
    public void shouldMatchSimilarStringAboveThreshold() {
        // single character typo - well above 0.8 similarity
        assertThat(matcher("some_value", 0.8d, false).matches(null, "some_valeu"), is(true));
    }

    @Test
    public void shouldNotMatchDissimilarStringBelowThreshold() {
        assertThat(matcher("some_value", 0.8d, false).matches(null, "totally_different_xyz"), is(false));
    }

    @Test
    public void shouldNotMatchWhenThresholdIsStrict() {
        // a typo that is similar but not similar enough for a 0.99 threshold
        assertThat(matcher("some_value", 0.99d, false).matches(null, "some_valeu"), is(false));
    }

    @Test
    public void shouldMatchWhenThresholdIsLenient() {
        assertThat(matcher("some_value", 0.5d, false).matches(null, "some_other_value"), is(true));
    }

    @Test
    public void shouldMatchCaseDifferenceOnlyWhenIgnoreCaseEnabled() {
        assertThat(matcher("HELLO WORLD", 0.99d, false).matches(null, "hello world"), is(false));
        assertThat(matcher("HELLO WORLD", 0.99d, true).matches(null, "hello world"), is(true));
    }

    @Test
    public void shouldTrimSurroundingWhitespaceWhenIgnoreCaseEnabled() {
        assertThat(matcher("hello", 0.99d, true).matches(null, "  hello  "), is(true));
    }

    @Test
    public void shouldMatchAnyValueWhenMatcherIsBlank() {
        assertThat(matcher("", 0.8d, false).matches(null, "anything"), is(true));
        assertThat(matcher(null, 0.8d, false).matches(null, "anything"), is(true));
    }

    @Test
    public void shouldReportBlankMatcher() {
        assertThat(matcher("", 0.8d, false).isBlank(), is(true));
        assertThat(matcher("some_value", 0.8d, false).isBlank(), is(false));
    }

    @Test
    public void shouldInvertResultWhenNotted() {
        assertThat(notMatcher(matcher("some_value", 0.8d, false)).matches(null, "some_value"), is(false));
        assertThat(notMatcher(matcher("some_value", 0.8d, false)).matches(null, "totally_different_xyz"), is(true));
    }
}
