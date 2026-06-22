package org.mockserver.matchers;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.core.Is.is;

/**
 * Unit tests for the RFC 7231 {@code Accept} header content-negotiation parser and matcher
 * ({@link AcceptHeaderMatcher}).
 *
 * @author jamesdbloom
 */
public class AcceptHeaderMatcherTest {

    // directive recognition

    @Test
    public void shouldRecognniseAcceptDirective() {
        assertThat(AcceptHeaderMatcher.isAcceptDirective("accept:application/json"), is(true));
        assertThat(AcceptHeaderMatcher.isAcceptDirective("ACCEPT:application/json"), is(true));
        assertThat(AcceptHeaderMatcher.isAcceptDirective("accept:*/*"), is(true));
    }

    @Test
    public void shouldNotRecogniseNonAcceptDirective() {
        assertThat(AcceptHeaderMatcher.isAcceptDirective("application/json"), is(false));
        assertThat(AcceptHeaderMatcher.isAcceptDirective("accept:"), is(false));
        assertThat(AcceptHeaderMatcher.isAcceptDirective("accept:   "), is(false));
        assertThat(AcceptHeaderMatcher.isAcceptDirective(null), is(false));
        assertThat(AcceptHeaderMatcher.isAcceptDirective(""), is(false));
        assertThat(AcceptHeaderMatcher.isAcceptDirective("acceptable:foo"), is(false));
    }

    // exact / wildcard acceptability

    @Test
    public void shouldMatchExactMediaType() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json"), is(true));
    }

    @Test
    public void shouldNotMatchDifferentMediaType() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "text/html"), is(false));
    }

    @Test
    public void shouldMatchViaFullWildcard() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "*/*"), is(true));
    }

    @Test
    public void shouldMatchViaSubtypeWildcard() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/*"), is(true));
        assertThat(AcceptHeaderMatcher.matches("accept:text/html", "application/*"), is(false));
    }

    @Test
    public void shouldMatchAmongMultipleRanges() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "text/html, application/json;q=0.9, */*;q=0.1"), is(true));
    }

    @Test
    public void shouldBeCaseInsensitiveOnMediaType() {
        assertThat(AcceptHeaderMatcher.matches("accept:Application/JSON", "application/json"), is(true));
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "APPLICATION/JSON"), is(true));
    }

    @Test
    public void shouldIgnoreParametersOnDesiredMediaType() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json; charset=utf-8", "application/json"), is(true));
    }

    // q=0 exclusion semantics

    @Test
    public void shouldNotMatchWhenExactRangeHasZeroQuality() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json;q=0"), is(false));
    }

    @Test
    public void shouldNotMatchWhenMoreSpecificRangeExcludesViaZeroQuality() {
        // */* accepts everything, but the more-specific application/json;q=0 excludes JSON
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "*/*, application/json;q=0"), is(false));
        // a different type is still accepted by */*
        assertThat(AcceptHeaderMatcher.matches("accept:text/html", "*/*, application/json;q=0"), is(true));
    }

    @Test
    public void shouldPreferMoreSpecificPositiveQualityOverBroaderZero() {
        // application/* excludes the family, but the exact application/json re-includes it
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/*;q=0, application/json;q=0.5"), is(true));
    }

    @Test
    public void shouldPreferHigherQualityAmongEquallySpecificDuplicateRanges() {
        // a duplicated exact range: any positive-q duplicate makes the type acceptable regardless of order
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json;q=0, application/json;q=0.9"), is(true));
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json;q=0.9, application/json;q=0"), is(true));
    }

    // blank / absent actual header

    @Test
    public void shouldNotMatchBlankAcceptHeader() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", ""), is(false));
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "   "), is(false));
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", null), is(false));
    }

    @Test
    public void shouldNotMatchWhenDirectiveMediaTypeMalformed() {
        assertThat(AcceptHeaderMatcher.matches("accept:notamediatype", "application/json"), is(false));
        assertThat(AcceptHeaderMatcher.matches("accept:/json", "application/json"), is(false));
        assertThat(AcceptHeaderMatcher.matches("accept:application/", "application/json"), is(false));
    }

    // lenient parsing of malformed ranges

    @Test
    public void shouldSkipMalformedRangesLeniently() {
        // "garbage" and "/html" are malformed and skipped; application/json still matches
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "garbage, /html, application/json"), is(true));
    }

    @Test
    public void shouldTreatMalformedQualityAsFullyAcceptable() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json;q=notanumber"), is(true));
    }

    @Test
    public void shouldClampQualityAboveOneAndBelowZero() {
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json;q=5"), is(true));
        assertThat(AcceptHeaderMatcher.matches("accept:application/json", "application/json;q=-1"), is(false));
    }

    // parser ordering: q desc, then specificity desc, then declaration order

    @Test
    public void shouldOrderByQualityDescending() {
        List<AcceptHeaderMatcher.MediaRange> ranges =
            AcceptHeaderMatcher.parseAcceptHeader("text/plain;q=0.3, application/json;q=0.9, text/html;q=0.6");
        assertThat(qualities(ranges), contains(0.9d, 0.6d, 0.3d));
    }

    @Test
    public void shouldOrderEqualQualityBySpecificityDescending() {
        List<AcceptHeaderMatcher.MediaRange> ranges =
            AcceptHeaderMatcher.parseAcceptHeader("*/*, application/*, application/json");
        // exact type/subtype = 3, type/* = 2, */* = 0
        assertThat(specificities(ranges), contains(3, 2, 0));
    }

    @Test
    public void shouldDefaultQualityToOne() {
        List<AcceptHeaderMatcher.MediaRange> ranges = AcceptHeaderMatcher.parseAcceptHeader("application/json");
        assertThat(qualities(ranges), contains(1.0d));
    }

    @Test
    public void shouldReturnEmptyForBlankHeader() {
        assertThat(AcceptHeaderMatcher.parseAcceptHeader(""), is(empty()));
        assertThat(AcceptHeaderMatcher.parseAcceptHeader(null), is(empty()));
    }

    @Test
    public void shouldRejectBareStarTypeWithNonStarSubtype() {
        // "*/json" is not a valid media range and is skipped
        assertThat(AcceptHeaderMatcher.parseAcceptHeader("*/json"), is(empty()));
    }

    private static List<Double> qualities(List<AcceptHeaderMatcher.MediaRange> ranges) {
        return ranges.stream().map(r -> r.quality).collect(java.util.stream.Collectors.toList());
    }

    private static List<Integer> specificities(List<AcceptHeaderMatcher.MediaRange> ranges) {
        return ranges.stream().map(AcceptHeaderMatcher.MediaRange::specificity).collect(java.util.stream.Collectors.toList());
    }
}
