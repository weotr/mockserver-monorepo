package org.mockserver.matchers;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link StatusCodeMatcher}.
 */
public class StatusCodeMatcherTest {

    // --- exact (default) behaviour: statusCodeRange null/blank -----------------------------------

    @Test
    public void shouldMatchExactStatusCodeWhenRangeIsNull() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(200, null);
        assertThat(matcher.matches(200), is(true));
        assertThat(matcher.matches(201), is(false));
        assertThat(matcher.matches(404), is(false));
    }

    @Test
    public void shouldMatchExactStatusCodeWhenRangeIsBlank() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(404, "   ");
        assertThat(matcher.matches(404), is(true));
        assertThat(matcher.matches(200), is(false));
    }

    @Test
    public void shouldMatchAllWhenBothExactAndRangeAreNull() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, null);
        assertThat(matcher.matches(200), is(true));
        assertThat(matcher.matches(500), is(true));
        assertThat(matcher.matches(0), is(true));
    }

    // --- class range ----------------------------------------------------------------------------

    @Test
    public void shouldMatchTwoHundredClassRange() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "2XX");
        assertThat(matcher.matches(200), is(true));
        assertThat(matcher.matches(201), is(true));
        assertThat(matcher.matches(299), is(true));
        assertThat(matcher.matches(300), is(false));
        assertThat(matcher.matches(404), is(false));
        assertThat(matcher.matches(199), is(false));
    }

    @Test
    public void shouldMatchClassRangeCaseInsensitively() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "2xx");
        assertThat(matcher.matches(204), is(true));
        assertThat(matcher.matches(404), is(false));
    }

    @Test
    public void shouldMatchClassRangeWithSurroundingWhitespace() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "  5XX  ");
        assertThat(matcher.matches(500), is(true));
        assertThat(matcher.matches(503), is(true));
        assertThat(matcher.matches(599), is(true));
        assertThat(matcher.matches(499), is(false));
        assertThat(matcher.matches(600), is(false));
    }

    @Test
    public void shouldMatchEachClassRange() {
        assertThat(new StatusCodeMatcher(null, "1XX").matches(100), is(true));
        assertThat(new StatusCodeMatcher(null, "3XX").matches(301), is(true));
        assertThat(new StatusCodeMatcher(null, "4XX").matches(404), is(true));
        assertThat(new StatusCodeMatcher(null, "4XX").matches(500), is(false));
    }

    @Test
    public void shouldIgnoreExactStatusCodeWhenRangeIsSet() {
        // when a range is supplied it drives the match, the exact code is not consulted
        StatusCodeMatcher matcher = new StatusCodeMatcher(200, "5XX");
        assertThat(matcher.matches(503), is(true));
        assertThat(matcher.matches(200), is(false));
    }

    // --- numeric operator -----------------------------------------------------------------------

    @Test
    public void shouldMatchGreaterThanOrEqualOperator() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, ">= 400");
        assertThat(matcher.matches(400), is(true));
        assertThat(matcher.matches(404), is(true));
        assertThat(matcher.matches(500), is(true));
        assertThat(matcher.matches(399), is(false));
        assertThat(matcher.matches(200), is(false));
    }

    @Test
    public void shouldMatchGreaterThanOperator() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "> 200");
        assertThat(matcher.matches(201), is(true));
        assertThat(matcher.matches(200), is(false));
        assertThat(matcher.matches(199), is(false));
    }

    @Test
    public void shouldMatchLessThanOperator() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "< 300");
        assertThat(matcher.matches(299), is(true));
        assertThat(matcher.matches(200), is(true));
        assertThat(matcher.matches(300), is(false));
        assertThat(matcher.matches(404), is(false));
    }

    @Test
    public void shouldMatchLessThanOrEqualOperator() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "<= 204");
        assertThat(matcher.matches(204), is(true));
        assertThat(matcher.matches(200), is(true));
        assertThat(matcher.matches(205), is(false));
    }

    @Test
    public void shouldMatchEqualsOperator() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "== 201");
        assertThat(matcher.matches(201), is(true));
        assertThat(matcher.matches(200), is(false));
    }

    @Test
    public void shouldMatchOperatorWithoutWhitespace() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, ">=400");
        assertThat(matcher.matches(404), is(true));
        assertThat(matcher.matches(200), is(false));
    }

    // --- unparseable ----------------------------------------------------------------------------

    @Test
    public void shouldNotMatchUnparseableExpression() {
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "not-a-range");
        assertThat(matcher.matches(200), is(false));
        assertThat(matcher.matches(500), is(false));
    }

    @Test
    public void shouldNotMatchMultiDigitClassExpression() {
        // "20X" is not a valid single-digit class range and has no numeric operator
        StatusCodeMatcher matcher = new StatusCodeMatcher(null, "20X");
        assertThat(matcher.matches(200), is(false));
    }
}
