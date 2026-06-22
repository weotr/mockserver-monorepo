package org.mockserver.responseheaders;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertFalse;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pure unit tests for {@link DefaultResponseHeaders} using a per-instance {@link org.mockserver.configuration.Configuration}
 * so no global {@code ConfigurationProperties} state is mutated.
 *
 * @author jamesdbloom
 */
public class DefaultResponseHeadersTest {

    @Test
    public void shouldNotChangeResponseWhenDefaultEmpty() {
        // given - default (no defaultResponseHeaders configured)
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(configuration());
        HttpResponse response = response("some_body");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then
        assertThat(defaultResponseHeaders.isEmpty(), is(true));
        assertThat(response, is(response("some_body")));
    }

    @Test
    public void shouldAddConfiguredDefaultHeaderToResponse() {
        // given
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("Server=MockServer")
        );
        HttpResponse response = response("some_body");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then
        assertThat(response.getFirstHeader("Server"), is("MockServer"));
    }

    @Test
    public void shouldNotOverwriteHeaderAlreadySetOnResponse() {
        // given - the response (e.g. the matched expectation) already sets Server
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("Server=MockServer")
        );
        HttpResponse response = response("some_body").withHeader("Server", "ExplicitValue");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then - the explicit response header wins (add-if-absent), no duplicate value added
        assertThat(response.getHeader("Server"), contains("ExplicitValue"));
    }

    @Test
    public void shouldNotOverwriteHeaderAlreadySetUsingDifferentCasing() {
        // given - response sets "server" (lower case), default configures "Server"
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("Server=MockServer")
        );
        HttpResponse response = response("some_body").withHeader("server", "ExplicitValue");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then - matched case-insensitively, explicit header wins and no second value added
        assertThat(response.getHeader("server"), contains("ExplicitValue"));
    }

    @Test
    public void shouldAddMultipleConfiguredDefaultHeaders() {
        // given
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("Server=MockServer|X-Trace-Id=abc123|X-Org= acme ")
        );
        HttpResponse response = response("some_body");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then
        assertThat(response.getFirstHeader("Server"), is("MockServer"));
        assertThat(response.getFirstHeader("X-Trace-Id"), is("abc123"));
        assertThat(response.getFirstHeader("X-Org"), is("acme"));
    }

    @Test
    public void shouldPreserveCommasWithinAHeaderValue() {
        // given - only '|' separates headers, so a value may contain commas
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("Cache-Control=no-cache, no-store")
        );
        HttpResponse response = response("some_body");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then
        assertThat(response.getFirstHeader("Cache-Control"), is("no-cache, no-store"));
    }

    @Test
    public void shouldPreserveEqualsSignsWithinAHeaderValue() {
        // given - only the first '=' splits name from value
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("X-Token=a=b=c")
        );
        HttpResponse response = response("some_body");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then
        assertThat(response.getFirstHeader("X-Token"), is("a=b=c"));
    }

    @Test
    public void shouldSkipMalformedSegments() {
        // given - blank segment, no '=', and a leading '=' (blank name) are all skipped
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(
            configuration().defaultResponseHeaders("|noEqualsHere|=blankName|Server=MockServer")
        );
        HttpResponse response = response("some_body");

        // when
        defaultResponseHeaders.addDefaultResponseHeaders(response);

        // then - only the valid pair is applied; the malformed segments are absent entirely
        // (not present with an empty value)
        assertThat(response.getFirstHeader("Server"), is("MockServer"));
        assertFalse(response.containsHeader("noEqualsHere"));
        assertFalse(response.containsHeader("blankName"));
    }

    @Test
    public void shouldMemoiseParseAcrossRepeatedConstruction() {
        // given - a single Configuration shared across many per-request ResponseWriter/DefaultResponseHeaders constructions
        Configuration configuration = configuration().defaultResponseHeaders("Server=MockServer|X-Trace-Id=abc123");

        // when - the parsed list is read twice (simulating two requests reusing the same configuration)
        Object first = configuration.parsedDefaultResponseHeaders();
        Object second = configuration.parsedDefaultResponseHeaders();

        // then - the same memoised list instance is reused, i.e. the pipe-split parse did not re-run
        assertThat(second, is(sameInstance(first)));
    }

    @Test
    public void shouldRecomputeMemoisedParseWhenConfiguredValueChanges() {
        // given
        Configuration configuration = configuration().defaultResponseHeaders("Server=MockServer");
        Object before = configuration.parsedDefaultResponseHeaders();

        // when - the configured value is changed
        configuration.defaultResponseHeaders("Server=Other");
        Object after = configuration.parsedDefaultResponseHeaders();

        // then - the cache was invalidated and recomputed
        assertThat(after, is(not(sameInstance(before))));
        DefaultResponseHeaders defaultResponseHeaders = new DefaultResponseHeaders(configuration);
        HttpResponse response = response("some_body");
        defaultResponseHeaders.addDefaultResponseHeaders(response);
        assertThat(response.getFirstHeader("Server"), is("Other"));
    }

}
