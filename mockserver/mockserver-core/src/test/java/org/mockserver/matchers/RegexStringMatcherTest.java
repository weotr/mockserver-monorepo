package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class RegexStringMatcherTest {

    @Test
    public void shouldMatchMatchingString() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("some_value"));
    }

    @Test
    public void shouldMatchUnMatchingNottedString() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, NottableString.not("not_value")));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcher() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_value"), false).matches("some_value"));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcherAndNottedString() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_matcher"), false).matches((MatchDifference) null, NottableString.not("not_value")));
    }

    @Test
    public void shouldNotMatchMatchingNottedString() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, NottableString.not("some_value")));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcher() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), false).matches("some_value"));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcherAndNottedString() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), false).matches((MatchDifference) null, NottableString.not("some_value")));
    }

    @Test
    public void shouldNotMatchMatchingString() {
        assertFalse(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("some_value"));
    }

    @Test
    public void shouldMatchMatchingStringWithRegexSymbols() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), false).matches("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
    }

    @Test
    public void shouldMatchMatchingRegex() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{5}"), false).matches("some_value"));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), string(null), false).matches("some_value"));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertTrue(new RegexStringMatcher(new MockServerLogger(), string(""), false).matches("some_value"));
    }

    @Test
    public void shouldNotMatchIncorrectString() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("not_matching"));
    }

    @Test
    public void shouldMatchIncorrectString() {
        assertTrue(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("not_matching"));
    }

    @Test
    public void shouldNotMatchMatchingControlPlaneRegex() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("some_[a-z]{5}"));
    }

    @Test
    public void shouldNotMatchIncorrectStringWithRegexSymbols() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), false).matches("text/html,application/xhtml+xml,application/xml;q=0.9;q=0.8"));
    }

    @Test
    public void shouldNotMatchIncorrectRegex() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{4}"), false).matches("some_value"));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, string(null)));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches(""));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectationAndTest() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("/{{}"));
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("some_value"));
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("/{}"));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectation() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("some_value"));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForTest() {
        assertFalse(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("/{}"));
    }

    @Test
    public void shouldReturnFalseOnReDoSPatternRatherThanHanging() {
        // (a+)+b backtracks exponentially on a long run of 'a' followed by a non-match.
        // Without the regex timeout this call would hang far longer than any test budget.
        long previousTimeout = org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis();
        try {
            org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis(200L);
            String evilInput = repeat('a', 40) + "c";
            long start = System.nanoTime();
            boolean matched = new RegexStringMatcher(new MockServerLogger(), string("(a+)+b"), false).matches(evilInput);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertFalse(matched);
            // generous upper bound: even at the 200ms timeout plus thread scheduling slack,
            // we should never approach minutes (which is what unbounded backtracking would cost).
            assertTrue("regex evaluation took " + elapsedMs + "ms, expected to be bounded by timeout",
                elapsedMs < 5_000L);
        } finally {
            org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis(previousTimeout);
        }
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }
}
