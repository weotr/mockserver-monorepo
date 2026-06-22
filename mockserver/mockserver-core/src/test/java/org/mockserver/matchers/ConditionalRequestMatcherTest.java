package org.mockserver.matchers;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.ConditionalRequestDefinition;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.ConditionalRequestDefinition.requestIf;
import static org.mockserver.model.HttpRequest.request;
import static org.mockito.Mockito.mock;

/**
 * @author jamesdbloom
 */
public class ConditionalRequestMatcherTest {

    private final Configuration configuration = configuration();
    private MockServerLogger mockServerLogger;

    @Before
    public void setupTestFixture() {
        mockServerLogger = mock(MockServerLogger.class);
    }

    private HttpRequestMatcher matcher(ConditionalRequestDefinition conditional) {
        return new MatcherBuilder(configuration, mockServerLogger).transformsToMatcher(new Expectation(conditional));
    }

    @Test
    public void shouldDispatchToConditionalMatcher() {
        assertThat(matcher(requestIf(request().withMethod("GET"))) instanceof ConditionalRequestMatcher, is(true));
    }

    // if-true-requires-then

    @Test
    public void shouldMatchWhenGuardTrueAndThenMatches() {
        // given - if method GET then path /admin
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        );

        // then
        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/admin")), is(true));
    }

    @Test
    public void shouldNotMatchWhenGuardTrueAndThenDoesNotMatch() {
        // given - if method GET then path /admin
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        );

        // then - guard true (GET) but then (/admin) not satisfied
        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/public")), is(false));
    }

    // if-false-requires-else

    @Test
    public void shouldMatchWhenGuardFalseAndElseMatches() {
        // given - if method GET then path /admin else path /public
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin"),
            request().withPath("/public")
        );

        // then - guard false (POST) so else (/public) is required and satisfied
        assertThat(matcher(conditional).matches(null, request().withMethod("POST").withPath("/public")), is(true));
    }

    @Test
    public void shouldNotMatchWhenGuardFalseAndElseDoesNotMatch() {
        // given - if method GET then path /admin else path /public
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin"),
            request().withPath("/public")
        );

        // then - guard false (POST) so else (/public) is required but not satisfied
        assertThat(matcher(conditional).matches(null, request().withMethod("POST").withPath("/admin")), is(false));
    }

    // else-absent semantics: match whenever the guard is false

    @Test
    public void shouldMatchWhenGuardFalseAndElseAbsent() {
        // given - if method GET then path /admin (no else)
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        );

        // then - guard false (POST) and no else => match regardless of other fields
        assertThat(matcher(conditional).matches(null, request().withMethod("POST").withPath("/anything")), is(true));
    }

    @Test
    public void shouldStillRequireThenWhenGuardTrueAndElseAbsent() {
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        );

        // guard true (GET) so then (/admin) still required
        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/admin")), is(true));
        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/other")), is(false));
    }

    // then absent => any request matching the guard matches

    @Test
    public void shouldMatchWhenGuardTrueAndThenAbsent() {
        ConditionalRequestDefinition conditional = requestIf(request().withMethod("GET"));

        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/anything")), is(true));
    }

    // compound sub-matchers (multiple fields ANDed within a branch)

    @Test
    public void shouldEvaluateCompoundSubMatchers() {
        // given - if (GET AND header X-Env: prod) then (path /v2 AND header X-Trace present)
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET").withHeader("X-Env", "prod"),
            request().withPath("/v2").withHeader("X-Trace", "abc")
        );

        // guard matches (GET + X-Env prod), then matches (/v2 + X-Trace abc)
        assertThat(matcher(conditional).matches(null,
            request().withMethod("GET").withPath("/v2")
                .withHeader(new Header("X-Env", "prod"))
                .withHeader(new Header("X-Trace", "abc"))), is(true));

        // guard matches but then fails (missing X-Trace)
        assertThat(matcher(conditional).matches(null,
            request().withMethod("GET").withPath("/v2")
                .withHeader(new Header("X-Env", "prod"))), is(false));

        // guard does not match (X-Env staging) and no else => matches
        assertThat(matcher(conditional).matches(null,
            request().withMethod("GET").withPath("/v2")
                .withHeader(new Header("X-Env", "staging"))), is(true));
    }

    // nested conditional

    @Test
    public void shouldEvaluateNestedConditional() {
        // given - if GET then (if header X-Env: prod then path /admin)
        ConditionalRequestDefinition nested = requestIf(
            request().withHeader("X-Env", "prod"),
            request().withPath("/admin")
        );
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            nested
        );

        // outer guard true, inner guard true, inner then satisfied
        assertThat(matcher(conditional).matches(null,
            request().withMethod("GET").withPath("/admin").withHeader(new Header("X-Env", "prod"))), is(true));

        // outer guard true, inner guard true, inner then NOT satisfied
        assertThat(matcher(conditional).matches(null,
            request().withMethod("GET").withPath("/other").withHeader(new Header("X-Env", "prod"))), is(false));

        // outer guard true, inner guard false (no inner else) => inner matches => overall match
        assertThat(matcher(conditional).matches(null,
            request().withMethod("GET").withPath("/other").withHeader(new Header("X-Env", "staging"))), is(true));
    }

    // getHttpRequests surfaces then/else branch requests for logging

    @Test
    public void shouldSurfaceThenAndElseRequests() {
        ConditionalRequestDefinition conditional = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin"),
            request().withPath("/public")
        );

        HttpRequestMatcher matcher = matcher(conditional);
        assertThat(matcher.getHttpRequests().size(), is(2));
        assertThat(matcher.getHttpRequests().contains(request().withPath("/admin")), is(true));
        assertThat(matcher.getHttpRequests().contains(request().withPath("/public")), is(true));
    }

    @Test
    public void shouldNotMatchWhenGuardAbsent() {
        // a conditional with no if guard cannot be evaluated
        ConditionalRequestDefinition conditional = new ConditionalRequestDefinition().withThen(request().withPath("/admin"));

        assertThat(matcher(conditional).matches(null, request().withPath("/admin")), is(false));
    }

    // not-operator: negates the overall conditional result

    @Test
    public void shouldNegateMatchWhenConditionalNotSet() {
        // given - NOT(if GET then /admin)
        ConditionalRequestDefinition conditional = (ConditionalRequestDefinition) requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        ).withNot(true);

        // guard true + then matches => base true => negated to false
        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/admin")), is(false));

        // guard true + then does not match => base false => negated to true
        assertThat(matcher(conditional).matches(null, request().withMethod("GET").withPath("/other")), is(true));
    }

    @Test
    public void shouldNegateAlwaysMatchWhenGuardFalseAndElseAbsentAndNotSet() {
        // given - NOT(if GET then /admin) with no else
        ConditionalRequestDefinition conditional = (ConditionalRequestDefinition) requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        ).withNot(true);

        // guard false + else absent => base always-true => negated to false
        assertThat(matcher(conditional).matches(null, request().withMethod("POST").withPath("/anything")), is(false));
    }
}
