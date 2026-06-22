package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.exact;

/**
 * Verifies the opt-in {@code matchExactCase} configuration flag.
 * <p>
 * Default (flag false) preserves the historical case-insensitive matching of method, path and string
 * body. When the flag is true, matching of those three fields becomes case-sensitive, while header,
 * cookie and query-string matching stays case-insensitive regardless of the flag.
 * <p>
 * Each test uses a per-instance {@link Configuration} (never the global singleton) so the test can run
 * in the parallel Surefire phase without mutating shared global state.
 *
 * @author jamesdbloom
 */
public class MatchExactCaseTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(MatchExactCaseTest.class);

    private HttpRequestPropertiesMatcher matcher(Configuration configuration, RequestDefinition requestDefinition) {
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(new Expectation(requestDefinition));
        return httpRequestPropertiesMatcher;
    }

    // DEFAULT (flag false) - current case-insensitive behaviour preserved

    @Test
    public void shouldDefaultToCaseInsensitiveMethod() {
        Configuration configuration = configuration();
        assertThat(configuration.matchExactCase(), is(false));
        assertThat(matcher(configuration, request().withMethod("GET")).matches(null, request().withMethod("get")), is(true));
        assertThat(matcher(configuration, request().withMethod("get")).matches(null, request().withMethod("GET")), is(true));
    }

    @Test
    public void shouldDefaultToCaseInsensitivePath() {
        Configuration configuration = configuration();
        assertThat(matcher(configuration, request().withPath("/Path")).matches(null, request().withPath("/path")), is(true));
        assertThat(matcher(configuration, request().withPath("/path")).matches(null, request().withPath("/PATH")), is(true));
    }

    @Test
    public void shouldDefaultToCaseInsensitiveRegexBody() {
        Configuration configuration = configuration();
        assertThat(matcher(configuration, request().withBody(regex("hello.*"))).matches(null, request().withBody("HELLO world")), is(true));
    }

    // FLAG TRUE - case-sensitive matching of method, path and string body

    @Test
    public void shouldMatchMethodCaseSensitivelyWhenEnabled() {
        Configuration configuration = configuration().matchExactCase(true);
        assertThat(configuration.matchExactCase(), is(true));
        // exact case matches
        assertThat(matcher(configuration, request().withMethod("GET")).matches(null, request().withMethod("GET")), is(true));
        // mixed case does not match
        assertThat(matcher(configuration, request().withMethod("GET")).matches(null, request().withMethod("get")), is(false));
        assertThat(matcher(configuration, request().withMethod("get")).matches(null, request().withMethod("GET")), is(false));
    }

    @Test
    public void shouldMatchPathCaseSensitivelyWhenEnabled() {
        Configuration configuration = configuration().matchExactCase(true);
        // exact case matches
        assertThat(matcher(configuration, request().withPath("/Path")).matches(null, request().withPath("/Path")), is(true));
        // mixed case does not match
        assertThat(matcher(configuration, request().withPath("/Path")).matches(null, request().withPath("/path")), is(false));
        assertThat(matcher(configuration, request().withPath("/path")).matches(null, request().withPath("/PATH")), is(false));
    }

    @Test
    public void shouldMatchRegexBodyCaseSensitivelyWhenEnabled() {
        Configuration configuration = configuration().matchExactCase(true);
        // exact case matches
        assertThat(matcher(configuration, request().withBody(regex("hello.*"))).matches(null, request().withBody("hello world")), is(true));
        // mixed case does not match
        assertThat(matcher(configuration, request().withBody(regex("hello.*"))).matches(null, request().withBody("HELLO world")), is(false));
    }

    @Test
    public void shouldMatchExactStringBodyCaseSensitively() {
        // ExactStringMatcher compares with String.equals, so an exact string body is always
        // case-sensitive regardless of matchExactCase. Asserted here (with the flag enabled) only to
        // document that the flag never weakens exact-string-body matching to case-insensitive.
        Configuration configuration = configuration().matchExactCase(true);
        assertThat(matcher(configuration, request().withBody(exact("Hello"))).matches(null, request().withBody("Hello")), is(true));
        assertThat(matcher(configuration, request().withBody(exact("Hello"))).matches(null, request().withBody("hello")), is(false));
    }

    // FLAG TRUE - headers (and other map fields) stay case-insensitive

    @Test
    public void shouldKeepHeaderNamesAndValuesCaseInsensitiveWhenEnabled() {
        Configuration configuration = configuration().matchExactCase(true);
        // header NAME mixed case still matches
        assertThat(
            matcher(configuration, request().withHeader(header("Content-Type", "text/plain")))
                .matches(null, request().withHeader(header("content-type", "text/plain"))),
            is(true)
        );
        // header VALUE mixed case still matches
        assertThat(
            matcher(configuration, request().withHeader(header("X-Custom", "SomeValue")))
                .matches(null, request().withHeader(header("X-Custom", "somevalue"))),
            is(true)
        );
    }

    @Test
    public void shouldKeepQueryStringParametersCaseInsensitiveWhenEnabled() {
        Configuration configuration = configuration().matchExactCase(true);
        assertThat(
            matcher(configuration, request().withQueryStringParameter("Param", "Value"))
                .matches(null, request().withQueryStringParameter("Param", "value")),
            is(true)
        );
    }

    @Test
    public void shouldKeepCookiesCaseInsensitiveWhenEnabled() {
        Configuration configuration = configuration().matchExactCase(true);
        assertThat(
            matcher(configuration, request().withCookie("Session", "AbC"))
                .matches(null, request().withCookie("Session", "abc")),
            is(true)
        );
    }
}
