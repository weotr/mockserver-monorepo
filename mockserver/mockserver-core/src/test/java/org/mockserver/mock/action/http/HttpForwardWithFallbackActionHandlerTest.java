package org.mockserver.mock.action.http;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpForwardWithFallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpForwardWithFallbackActionHandlerTest {

    private HttpForwardWithFallbackActionHandler handler;
    private NettyHttpClient mockHttpClient;

    @Before
    public void setup() {
        mockHttpClient = mock(NettyHttpClient.class);
        MockServerLogger mockServerLogger = mock(MockServerLogger.class);
        handler = new HttpForwardWithFallbackActionHandler(mockServerLogger, Configuration.configuration(), mockHttpClient);
    }

    @Test
    public void shouldReturnUpstreamResponseWhen2xx() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(200).withBody("upstream OK");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("upstream OK"));
    }

    @Test
    public void shouldReturnFallbackWhenUpstreamReturns5xx() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(503).withBody("Service Unavailable");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback response");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("fallback response"));
    }

    @Test
    public void shouldReturnFallbackWhenUpstreamReturns500() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(500);
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("cached");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("cached"));
    }

    @Test
    public void shouldReturnFallbackWhenUpstreamReturns599() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(599);
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("fallback"));
    }

    @Test
    public void shouldNotFallbackOn4xx() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(404).withBody("Not Found");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then - upstream 404 should be returned as-is
        assertThat(result.getStatusCode(), is(404));
        assertThat(result.getBodyAsString(), is("Not Found"));
    }

    @Test
    public void shouldFallbackOnCustomStatusCodes() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(429).withBody("Too Many Requests");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("rate-limited fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback)
            .withFallbackOnStatusCodes(429, 503);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("rate-limited fallback"));
    }

    @Test
    public void shouldNotFallbackOnStatusCodeNotInCustomList() throws Exception {
        // given
        HttpResponse upstreamResponse = response().withStatusCode(500).withBody("Server Error");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        // only fallback on 429 and 503, NOT on 500
        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback)
            .withFallbackOnStatusCodes(429, 503);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then - 500 is not in the custom list so upstream response returned as-is
        assertThat(result.getStatusCode(), is(500));
        assertThat(result.getBodyAsString(), is("Server Error"));
    }

    @Test
    public void shouldFallbackOnTimeout() throws Exception {
        // given
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        responseFuture.completeExceptionally(new RuntimeException("Connection timed out"));

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("timeout fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("timeout fallback"));
    }

    @Test
    public void shouldNotFallbackOnTimeoutWhenDisabled() throws Exception {
        // given
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        responseFuture.completeExceptionally(new RuntimeException("Connection timed out"));

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback)
            .withFallbackOnTimeout(false);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then - should return bad gateway (not fallback) since timeout fallback is disabled
        assertThat(result.getStatusCode(), is(502));
    }

    @Test
    public void shouldReturnBadGatewayWhenNullAction() throws Exception {
        // given
        HttpRequest httpRequest = request().withPath("/api");

        // when
        HttpResponse result = handler.handle(null, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(502));
    }

    @Test
    public void shouldReturnBadGatewayWhenNullForward() throws Exception {
        // given
        HttpRequest httpRequest = request().withPath("/api");
        HttpForwardWithFallback action = forwardWithFallback()
            .withFallback(response().withStatusCode(200).withBody("fallback"));

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(502));
    }

    @Test
    public void shouldFallbackOnNullResponse() throws Exception {
        // given
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(null);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("null response fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        assertThat(result.getBodyAsString(), is("null response fallback"));
    }

    @Test
    public void shouldReturnUpstreamResponseWhenNotIn5xxRange() throws Exception {
        // given - test boundary: 499 should not trigger fallback
        HttpResponse upstreamResponse = response().withStatusCode(499).withBody("upstream 499");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(499));
        assertThat(result.getBodyAsString(), is("upstream 499"));
    }

    @Test
    public void shouldReturnUpstreamResponseWhenNotIn5xxRangeUpperBound() throws Exception {
        // given - test boundary: 600 should not trigger fallback
        HttpResponse upstreamResponse = response().withStatusCode(600).withBody("upstream 600");
        CompletableFuture<HttpResponse> responseFuture = CompletableFuture.completedFuture(upstreamResponse);

        HttpRequest httpRequest = request().withPath("/api");
        HttpForward httpForward = forward().withHost("upstream.example.com").withPort(8080);
        HttpResponse fallback = response().withStatusCode(200).withBody("fallback");

        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(responseFuture);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(httpForward)
            .withFallback(fallback);

        // when
        HttpResponse result = handler.handle(action, httpRequest)
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(600));
        assertThat(result.getBodyAsString(), is("upstream 600"));
    }

    @Test
    public void shouldShouldFallbackMethodDefaultsTo5xx() {
        // 500-599 should trigger fallback when no custom codes specified
        assertThat(handler.shouldFallback(500, null), is(true));
        assertThat(handler.shouldFallback(503, null), is(true));
        assertThat(handler.shouldFallback(599, null), is(true));
        assertThat(handler.shouldFallback(499, null), is(false));
        assertThat(handler.shouldFallback(600, null), is(false));
        assertThat(handler.shouldFallback(200, null), is(false));
        assertThat(handler.shouldFallback(404, null), is(false));
    }

    @Test
    public void shouldShouldFallbackMethodUsesCustomCodes() {
        assertThat(handler.shouldFallback(429, Arrays.asList(429, 503)), is(true));
        assertThat(handler.shouldFallback(503, Arrays.asList(429, 503)), is(true));
        assertThat(handler.shouldFallback(500, Arrays.asList(429, 503)), is(false));
        assertThat(handler.shouldFallback(200, Arrays.asList(429, 503)), is(false));
    }

    @Test
    public void shouldShouldFallbackMethodHandlesEmptyList() {
        // empty list treated as "no custom codes" -> defaults to 5xx
        assertThat(handler.shouldFallback(500, Arrays.asList()), is(true));
        assertThat(handler.shouldFallback(200, Arrays.asList()), is(false));
    }

    @Test
    public void shouldShouldFallbackMethodHandlesNullStatusCode() {
        assertThat(handler.shouldFallback(null, null), is(true));
    }

    @Test
    public void shouldGetActionType() {
        HttpForwardWithFallback action = forwardWithFallback();
        assertThat(action.getType(), is(org.mockserver.model.Action.Type.FORWARD_WITH_FALLBACK));
    }
}
