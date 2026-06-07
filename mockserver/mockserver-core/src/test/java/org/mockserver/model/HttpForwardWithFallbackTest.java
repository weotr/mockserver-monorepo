package org.mockserver.model;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.model.HttpForwardWithFallbackDTO;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpForwardWithFallbackTest {

    @Test
    public void shouldCreateWithStaticBuilder() {
        HttpForwardWithFallback action = forwardWithFallback();
        assertThat(action, is(notNullValue()));
        assertThat(action.getType(), is(Action.Type.FORWARD_WITH_FALLBACK));
    }

    @Test
    public void shouldSetAndGetFields() {
        HttpForward httpForward = forward().withHost("api.example.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);
        HttpResponse fallback = response().withStatusCode(200).withBody("cached response");
        List<Integer> statusCodes = Arrays.asList(502, 503, 504);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback)
            .withFallbackOnStatusCodes(statusCodes)
            .withFallbackOnTimeout(true);

        assertThat(action.getHttpForward(), is(httpForward));
        assertThat(action.getFallbackResponse(), is(fallback));
        assertThat(action.getFallbackOnStatusCodes(), is(statusCodes));
        assertThat(action.getFallbackOnTimeout(), is(true));
    }

    @Test
    public void shouldSetStatusCodesVarargs() {
        HttpForwardWithFallback action = forwardWithFallback()
            .withFallbackOnStatusCodes(429, 502, 503);

        assertThat(action.getFallbackOnStatusCodes(), contains(429, 502, 503));
    }

    @Test
    public void shouldSupportEqualsAndHashCode() {
        HttpForwardWithFallback action1 = forwardWithFallback()
            .withForward(forward().withHost("host1").withPort(80))
            .withFallback(response().withStatusCode(200))
            .withFallbackOnStatusCodes(500, 503)
            .withFallbackOnTimeout(true);

        HttpForwardWithFallback action2 = forwardWithFallback()
            .withForward(forward().withHost("host1").withPort(80))
            .withFallback(response().withStatusCode(200))
            .withFallbackOnStatusCodes(500, 503)
            .withFallbackOnTimeout(true);

        assertThat(action1, is(action2));
        assertThat(action1.hashCode(), is(action2.hashCode()));
    }

    @Test
    public void shouldSupportNotEquals() {
        HttpForwardWithFallback action1 = forwardWithFallback()
            .withForward(forward().withHost("host1").withPort(80))
            .withFallback(response().withStatusCode(200));

        HttpForwardWithFallback action2 = forwardWithFallback()
            .withForward(forward().withHost("host2").withPort(80))
            .withFallback(response().withStatusCode(200));

        assertThat(action1, is(not(action2)));
    }

    @Test
    public void shouldRoundTripThroughDTO() {
        HttpForwardWithFallback original = forwardWithFallback()
            .withForward(forward().withHost("api.example.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS))
            .withFallback(response().withStatusCode(200).withBody("cached"))
            .withFallbackOnStatusCodes(500, 502, 503)
            .withFallbackOnTimeout(true);

        HttpForwardWithFallbackDTO dto = new HttpForwardWithFallbackDTO(original);
        HttpForwardWithFallback rebuilt = dto.buildObject();

        assertThat(rebuilt.getHttpForward().getHost(), is("api.example.com"));
        assertThat(rebuilt.getHttpForward().getPort(), is(443));
        assertThat(rebuilt.getHttpForward().getScheme(), is(HttpForward.Scheme.HTTPS));
        assertThat(rebuilt.getFallbackResponse().getStatusCode(), is(200));
        assertThat(rebuilt.getFallbackOnStatusCodes(), contains(500, 502, 503));
        assertThat(rebuilt.getFallbackOnTimeout(), is(true));
    }

    @Test
    public void shouldRoundTripThroughDTOWithNulls() {
        HttpForwardWithFallback original = forwardWithFallback();

        HttpForwardWithFallbackDTO dto = new HttpForwardWithFallbackDTO(original);
        HttpForwardWithFallback rebuilt = dto.buildObject();

        assertThat(rebuilt.getHttpForward(), is(nullValue()));
        assertThat(rebuilt.getFallbackResponse(), is(nullValue()));
        assertThat(rebuilt.getFallbackOnStatusCodes(), is(nullValue()));
        assertThat(rebuilt.getFallbackOnTimeout(), is(nullValue()));
    }

    @Test
    public void shouldIntegrateWithExpectation() {
        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(forward().withHost("api.example.com").withPort(8080))
            .withFallback(response().withStatusCode(200).withBody("fallback"));

        Expectation expectation = Expectation.when(request().withPath("/test"))
            .thenForwardWithFallback(action);

        assertThat(expectation.getHttpForwardWithFallback(), is(action));
        assertThat(expectation.getAction(), is(action));
        assertThat(expectation.getAction().getType(), is(Action.Type.FORWARD_WITH_FALLBACK));
    }

    @Test
    public void shouldCloneExpectationWithForwardWithFallback() {
        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(forward().withHost("api.example.com").withPort(8080))
            .withFallback(response().withStatusCode(200).withBody("fallback"));

        Expectation original = Expectation.when(request().withPath("/test"))
            .thenForwardWithFallback(action);

        Expectation cloned = original.clone();

        assertThat(cloned.getHttpForwardWithFallback(), is(action));
        assertThat(cloned.getAction().getType(), is(Action.Type.FORWARD_WITH_FALLBACK));
    }
}
