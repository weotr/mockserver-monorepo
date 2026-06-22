package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Verifies that, after a data-plane match against an expectation with a regex path containing capture
 * groups, the matched request carries the numbered/named groups so a response/forward template can read
 * them.
 *
 * @author jamesdbloom
 */
public class PathGroupMatchingTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger();

    private HttpRequestPropertiesMatcher matcherFor(HttpRequest expectationRequest) {
        HttpRequestPropertiesMatcher matcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        matcher.update(new Expectation(expectationRequest));
        return matcher;
    }

    @Test
    public void shouldAttachNumberedPathGroupsAfterMatch() {
        // given
        HttpRequestPropertiesMatcher matcher = matcherFor(request().withPath("/users/(\\d+)/orders/(\\w+)"));
        HttpRequest actual = request().withPath("/users/42/orders/abc");

        // when
        boolean matched = matcher.matches(null, actual);

        // then
        assertThat(matched, is(true));
        assertThat(actual.getPathGroups(), is(notNullValue()));
        assertThat(actual.getPathGroups().get(0), is("/users/42/orders/abc"));
        assertThat(actual.getPathGroups().get(1), is("42"));
        assertThat(actual.getPathGroups().get(2), is("abc"));
    }

    @Test
    public void shouldAttachNamedPathGroupsAfterMatch() {
        // given
        HttpRequestPropertiesMatcher matcher = matcherFor(request().withPath("/users/(?<userId>\\d+)"));
        HttpRequest actual = request().withPath("/users/7");

        // when
        boolean matched = matcher.matches(null, actual);

        // then
        assertThat(matched, is(true));
        assertThat(actual.getNamedPathGroups(), hasEntry("userId", "7"));
        assertThat(actual.getPathGroups().get(1), is("7"));
    }

    @Test
    public void shouldNotAttachGroupsForLiteralPathMatch() {
        // given
        HttpRequestPropertiesMatcher matcher = matcherFor(request().withPath("/users/all"));
        HttpRequest actual = request().withPath("/users/all");

        // when
        boolean matched = matcher.matches(null, actual);

        // then
        assertThat(matched, is(true));
        assertThat(actual.getPathGroups(), is(nullValue()));
        assertThat(actual.getNamedPathGroups(), is(nullValue()));
    }

    @Test
    public void shouldClearStaleGroupsWhenASubsequentMatchingExpectationHasNoGroups() {
        // a request is matched against many expectations in one scan reusing the same request object; an
        // earlier regex expectation may match (setting groups) yet be skipped, with a later literal/no-group
        // expectation actually served. The served match must not carry the earlier candidate's groups.
        HttpRequest actual = request().withPath("/users/42");

        // first matcher (regex) matches and sets groups
        assertThat(matcherFor(request().withPath("/users/(\\d+)")).matches(null, actual), is(true));
        assertThat(actual.getPathGroups(), is(notNullValue()));

        // second matcher (literal, no capturing group) matches the same request object
        assertThat(matcherFor(request().withPath("/users/42")).matches(null, actual), is(true));

        // then the stale groups from the first matcher must have been cleared
        assertThat(actual.getPathGroups(), is(nullValue()));
        assertThat(actual.getNamedPathGroups(), is(nullValue()));
    }

    @Test
    public void shouldAttachGroupsForCaseInsensitiveDefaultMatch() {
        // the default path matcher is case-insensitive, so a pattern letter differing only in case from the
        // request path still matches and must still extract groups
        HttpRequestPropertiesMatcher matcher = matcherFor(request().withPath("/Users/([a-z]+)"));
        HttpRequest actual = request().withPath("/users/bob");

        // when
        boolean matched = matcher.matches(null, actual);

        // then
        assertThat(matched, is(true));
        assertThat(actual.getPathGroups().get(1), is("bob"));
    }

    @Test
    public void shouldNotAttachGroupsWhenRequestDoesNotMatch() {
        // given
        HttpRequestPropertiesMatcher matcher = matcherFor(request().withPath("/users/(\\d+)"));
        HttpRequest actual = request().withPath("/users/bob");

        // when
        boolean matched = matcher.matches(null, actual);

        // then
        assertThat(matched, is(false));
        assertThat(actual.getPathGroups(), is(nullValue()));
    }
}
