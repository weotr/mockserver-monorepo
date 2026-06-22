package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.NottableString.string;

/**
 * Behavioural tests for {@code accept:<media-type>} content-negotiation matching of a single
 * header value, exercised through the same key/value map path
 * ({@link org.mockserver.collections.NottableStringMultiMap}) used for real header matching. That
 * path builds a {@link RegexStringMatcher} via the no-fixed-value constructor and calls the
 * two-argument {@code matches(matcher, matched)} form, which enables the {@code accept:} directive;
 * the fixed-value constructor (method/path/body/reason-phrase) does not.
 *
 * @author jamesdbloom
 */
public class AcceptHeaderRegexStringMatcherTest {

    // the no-fixed-value constructor == the headers/cookies/query/params matching path
    private boolean matches(String expected, String actualAcceptHeader) {
        return new RegexStringMatcher(new MockServerLogger(), false).matches(string(expected), string(actualAcceptHeader));
    }

    private boolean matchesNottable(String expected, NottableString actual) {
        return new RegexStringMatcher(new MockServerLogger(), false).matches(string(expected), actual);
    }

    @Test
    public void shouldMatchWhenMediaTypeAcceptable() {
        assertThat(matches("accept:application/json", "text/html, application/json;q=0.9"), is(true));
    }

    @Test
    public void shouldNotMatchWhenMediaTypeNotAcceptable() {
        assertThat(matches("accept:application/json", "text/html, text/plain"), is(false));
    }

    @Test
    public void shouldMatchViaWildcard() {
        assertThat(matches("accept:application/json", "*/*"), is(true));
        assertThat(matches("accept:application/json", "application/*"), is(true));
    }

    @Test
    public void shouldNotMatchWhenExcludedByZeroQuality() {
        assertThat(matches("accept:application/json", "*/*, application/json;q=0"), is(false));
    }

    @Test
    public void shouldNotMatchBlankAcceptHeader() {
        assertThat(matches("accept:application/json", ""), is(false));
    }

    // negation via NottableString — the ! prefix inverts the whole acceptability result

    @Test
    public void shouldInvertAcceptResultViaNottedMatcher() {
        // "!accept:application/json" means NOT (json acceptable)
        assertThat(matches("!accept:application/json", "text/html"), is(true));
        assertThat(matches("!accept:application/json", "application/json"), is(false));
    }

    @Test
    public void shouldInvertAcceptResultViaNottedMatchedValue() {
        assertThat(matchesNottable("accept:application/json", NottableString.not("application/json")), is(false));
        assertThat(matchesNottable("accept:application/json", NottableString.not("text/html")), is(true));
    }

    // backward-compatibility regression — plain values and regex are unchanged

    @Test
    public void shouldStillMatchPlainHeaderValueExactly() {
        // no "accept:" directive => normal exact/regex string matching
        assertThat(matches("application/json", "application/json"), is(true));
        assertThat(matches("application/json", "text/html"), is(false));
    }

    @Test
    public void shouldStillMatchValueThatMerelyContainsAcceptWord() {
        // "acceptable" is not the "accept:" directive — exact string matching applies
        assertThat(matches("acceptable", "acceptable"), is(true));
    }

    // the critical backward-compatibility guard: the accept directive must NOT leak into
    // the fixed-value matching path used for regex body / path / method / reason-phrase

    @Test
    public void shouldNotApplyAcceptDirectiveToFixedValueMatcherSuchAsRegexBody() {
        RegexStringMatcher fixedValueMatcher = new RegexStringMatcher(new MockServerLogger(), string("accept:application/json"), false);
        // exact string match preserved; NOT interpreted as content-negotiation
        assertThat(fixedValueMatcher.matches("accept:application/json"), is(true));
        assertThat(fixedValueMatcher.matches("application/json"), is(false));
    }
}
