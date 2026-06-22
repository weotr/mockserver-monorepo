package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.NottableString.string;

/**
 * Behavioural tests for numeric comparison operator matching of single
 * header / cookie / query-string parameter values. These are matched through the
 * key/value map path ({@link org.mockserver.collections.NottableStringMultiMap} /
 * {@link org.mockserver.collections.NottableStringHashMap}), which build a
 * {@link RegexStringMatcher} via the no-fixed-value constructor and call the
 * two-argument {@code matches(matcher, matched)} form. That path enables numeric
 * comparison; the fixed-value constructor (method/path/body/reason-phrase) does not.
 *
 * @author jamesdbloom
 */
public class NumericComparisonMatcherTest {

    // the no-fixed-value constructor == the headers/cookies/query/params matching path
    private boolean matches(String expected, String actual) {
        return new RegexStringMatcher(new MockServerLogger(), false).matches(string(expected), string(actual));
    }

    private boolean matchesNottable(String expected, NottableString actual) {
        return new RegexStringMatcher(new MockServerLogger(), false).matches(string(expected), actual);
    }

    // greater than

    @Test
    public void shouldMatchGreaterThanWhenActualIsGreater() {
        assertThat(matches("> 60", "61"), is(true));
    }

    @Test
    public void shouldNotMatchGreaterThanWhenActualEqualsBoundary() {
        assertThat(matches("> 60", "60"), is(false));
    }

    @Test
    public void shouldNotMatchGreaterThanForLeadingZeroNumberBelowBoundary() {
        // "059" parses numerically to 59, which is not > 60
        assertThat(matches("> 60", "059"), is(false));
    }

    @Test
    public void shouldNotMatchGreaterThanForNonNumericActualValue() {
        assertThat(matches("> 60", "abc"), is(false));
    }

    // greater than or equal (boundary)

    @Test
    public void shouldMatchGreaterThanOrEqualAtBoundary() {
        assertThat(matches(">= 60", "60"), is(true));
    }

    @Test
    public void shouldMatchGreaterThanOrEqualAboveBoundary() {
        assertThat(matches(">= 60", "61"), is(true));
    }

    @Test
    public void shouldNotMatchGreaterThanOrEqualBelowBoundary() {
        assertThat(matches(">= 60", "59"), is(false));
    }

    // less than / less than or equal (boundary)

    @Test
    public void shouldMatchLessThanOrEqualAtBoundary() {
        assertThat(matches("<= 30", "30"), is(true));
    }

    @Test
    public void shouldMatchLessThanOrEqualBelowBoundary() {
        assertThat(matches("<= 30", "29"), is(true));
    }

    @Test
    public void shouldNotMatchLessThanOrEqualAboveBoundary() {
        assertThat(matches("<= 30", "31"), is(false));
    }

    @Test
    public void shouldMatchLessThan() {
        assertThat(matches("< 100", "99"), is(true));
        assertThat(matches("< 100", "100"), is(false));
    }

    // equal / not equal

    @Test
    public void shouldMatchEqualsOperator() {
        assertThat(matches("== 5", "5"), is(true));
        assertThat(matches("== 5", "6"), is(false));
    }

    @Test
    public void shouldMatchNotEqualsViaNottedEqualsOperator() {
        // not-equal is expressed by negating equality with the NottableString "!" prefix
        assertThat(matches("!== 5", "6"), is(true));
        assertThat(matches("!== 5", "5"), is(false));
    }

    // decimals, negatives and whitespace-optional syntax

    @Test
    public void shouldMatchDecimalComparison() {
        assertThat(matches(">= 1.5", "1.5"), is(true));
        assertThat(matches(">= 1.5", "1.4"), is(false));
    }

    @Test
    public void shouldMatchNegativeComparison() {
        assertThat(matches("> -1", "0"), is(true));
        assertThat(matches("> -1", "-2"), is(false));
    }

    @Test
    public void shouldMatchOperatorWithoutWhitespace() {
        assertThat(matches(">60", "61"), is(true));
        assertThat(matches(">60", "60"), is(false));
    }

    // negation via NottableString — the ! prefix inverts the whole numeric result

    @Test
    public void shouldInvertNumericComparisonViaNottedMatcher() {
        // "!> 60" means NOT (value > 60)
        assertThat(matches("!> 60", "61"), is(false));
        assertThat(matches("!> 60", "60"), is(true));
        assertThat(matches("!> 60", "abc"), is(true));
    }

    @Test
    public void shouldInvertNumericComparisonViaNottedMatchedValue() {
        assertThat(matchesNottable("> 60", NottableString.not("61")), is(false));
        assertThat(matchesNottable("> 60", NottableString.not("60")), is(true));
    }

    // backward-compatibility regression — plain value and regex are unchanged

    @Test
    public void shouldStillMatchPlainNumericValueExactly() {
        // no operator => exact string match, NOT numeric
        assertThat(matches("60", "60"), is(true));
        assertThat(matches("60", "60.0"), is(false));
        assertThat(matches("60", "061"), is(false));
    }

    @Test
    public void shouldStillMatchRegexValueExactly() {
        assertThat(matches("[0-9]+", "12345"), is(true));
        assertThat(matches("[0-9]+", "abc"), is(false));
    }

    @Test
    public void shouldNotTreatTextStartingWithOperatorWordAsNumericWhenNoNumber() {
        // "> abc" is not a valid numeric operator expression, so falls back to
        // exact / regex string matching (and matches itself exactly)
        assertThat(matches("> abc", "> abc"), is(true));
        assertThat(matches("> abc", "61"), is(false));
    }

    // the critical backward-compatibility guard: numeric comparison must NOT leak into
    // the fixed-value matching path used for regex body / path / method / reason-phrase

    @Test
    public void shouldNotApplyNumericComparisonToFixedValueMatcherSuchAsRegexBody() {
        // BodyMatcherBuilder builds REGEX body matchers with the fixed-value constructor.
        // A regex body of "== 5" must keep exact/regex string semantics, NOT numeric.
        RegexStringMatcher fixedValueMatcher = new RegexStringMatcher(new MockServerLogger(), string("== 5"), false);
        assertThat(fixedValueMatcher.matches("== 5"), is(true));  // exact string match preserved
        assertThat(fixedValueMatcher.matches("5"), is(false));    // must NOT be treated as numeric-equal
    }
}
