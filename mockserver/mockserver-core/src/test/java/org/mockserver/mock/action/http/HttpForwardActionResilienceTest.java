package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end wiring of the forward retry policy and per-upstream circuit breaker through
 * {@link HttpForwardActionHandler}. Uses a mocked {@link NettyHttpClient} so the (retry,
 * circuit-breaker) decisions are exercised without a live upstream.
 *
 * <p>Mutates the {@link ForwardCircuitBreaker} singleton via {@code getInstance().reset()} so it is
 * registered in the sequential Surefire phase of {@code mockserver-core/pom.xml}.
 */
public class HttpForwardActionResilienceTest {

    private NettyHttpClient mockHttpClient;

    private static CompletableFuture<HttpResponse> ok() {
        return CompletableFuture.completedFuture(response().withStatusCode(200).withBody("ok"));
    }

    private static CompletableFuture<HttpResponse> failed(int statusCode) {
        return CompletableFuture.completedFuture(response().withStatusCode(statusCode));
    }

    @Before
    public void setup() {
        mockHttpClient = mock(NettyHttpClient.class);
        ForwardCircuitBreaker.getInstance().reset();
    }

    @After
    public void teardown() {
        ForwardCircuitBreaker.getInstance().reset();
    }

    private HttpForwardActionHandler handlerWith(Configuration configuration) {
        return new HttpForwardActionHandler(mock(MockServerLogger.class), configuration, mockHttpClient);
    }

    private static HttpForward upstream() {
        return forward().withHost("upstream.example").withPort(8080).withScheme(HttpForward.Scheme.HTTP);
    }

    @Test
    public void shouldNotRetryByDefault() throws Exception {
        // given - default configuration (retry disabled)
        Configuration configuration = Configuration.configuration();
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(failed(503));

        // when
        HttpResponse result = handlerWith(configuration)
            .handle(upstream(), request().withMethod("GET").withPath("/x"))
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then - single attempt, 503 surfaced unchanged
        assertThat(result.getStatusCode(), is(503));
        verify(mockHttpClient, times(1)).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    @Test
    public void shouldRetryIdempotentRequestAndSucceedAfterFailures() throws Exception {
        // given - 2 retries; first two attempts 503, third 200
        Configuration configuration = Configuration.configuration()
            .forwardProxyRetryCount(2)
            .forwardProxyRetryBackoffMillis(0L);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class)))
            .thenReturn(failed(503), failed(503), ok());

        // when
        HttpResponse result = handlerWith(configuration)
            .handle(upstream(), request().withMethod("GET").withPath("/x"))
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        // then
        assertThat(result.getStatusCode(), is(200));
        verify(mockHttpClient, times(3)).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    @Test
    public void shouldNotRetryNonIdempotentRequest() throws Exception {
        Configuration configuration = Configuration.configuration()
            .forwardProxyRetryCount(3)
            .forwardProxyRetryBackoffMillis(0L);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(failed(503));

        HttpResponse result = handlerWith(configuration)
            .handle(upstream(), request().withMethod("POST").withPath("/x"))
            .getHttpResponse()
            .get(5, TimeUnit.SECONDS);

        assertThat(result.getStatusCode(), is(503));
        verify(mockHttpClient, times(1)).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    @Test
    public void shouldOpenCircuitAfterConsecutiveFailuresAndFailFast() throws Exception {
        // given - circuit breaker enabled, threshold 3, retry disabled
        Configuration configuration = Configuration.configuration()
            .forwardProxyCircuitBreakerEnabled(true)
            .forwardProxyCircuitBreakerFailureThreshold(3)
            .forwardProxyCircuitBreakerWindowMillis(60_000L);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(failed(503));

        HttpForwardActionHandler handler = handlerWith(configuration);

        // when - 3 consecutive failures open the breaker
        for (int i = 0; i < 3; i++) {
            handler.handle(upstream(), request().withMethod("GET").withPath("/x"))
                .getHttpResponse().get(5, TimeUnit.SECONDS);
        }

        // then - the breaker is now open for this upstream
        assertThat(ForwardCircuitBreaker.getInstance().isOpen("upstream.example:8080"), is(true));
        assertThat(MetricsHelper.openCount(), is(1));

        // and - the next request fails fast with a 503 WITHOUT reaching the client
        reset(mockHttpClient);
        HttpResponse fastFail = handler.handle(upstream(), request().withMethod("GET").withPath("/x"))
            .getHttpResponse().get(5, TimeUnit.SECONDS);
        assertThat(fastFail.getStatusCode(), is(503));
        assertThat(fastFail.getBodyAsString(), containsString("circuit breaker open"));
        verify(mockHttpClient, never()).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    @Test
    public void shouldNotStrandOpenWhenHalfOpenTrialThrowsSynchronously() throws Exception {
        // given - breaker enabled, threshold 3, short window so we can reach half-open
        Configuration configuration = Configuration.configuration()
            .forwardProxyCircuitBreakerEnabled(true)
            .forwardProxyCircuitBreakerFailureThreshold(3)
            .forwardProxyCircuitBreakerWindowMillis(1L);
        // every forward attempt throws synchronously (e.g. connection refused before any future)
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class)))
            .thenThrow(new RuntimeException("connection refused"));

        HttpForwardActionHandler handler = handlerWith(configuration);

        // when - 3 synchronous failures open the breaker (proving sync failures are counted)
        for (int i = 0; i < 3; i++) {
            handler.handle(upstream(), request().withMethod("GET").withPath("/x"))
                .getHttpResponse().get(5, TimeUnit.SECONDS);
        }
        assertThat(ForwardCircuitBreaker.getInstance().isOpen("upstream.example:8080"), is(true));

        // and - after the 1ms window a half-open trial is admitted but also throws synchronously
        Thread.sleep(5);
        handler.handle(upstream(), request().withMethod("GET").withPath("/x"))
            .getHttpResponse().get(5, TimeUnit.SECONDS);

        // then - the breaker is NOT stranded: the failed sync trial released the slot and re-opened,
        // so after another window a fresh trial is admitted again (recovery can still be probed)
        assertThat(ForwardCircuitBreaker.getInstance().isOpen("upstream.example:8080"), is(true));
        Thread.sleep(5);
        // a now-recovering upstream returns 200 on the next admitted trial and the breaker closes
        reset(mockHttpClient);
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(ok());
        HttpResponse recovered = handler.handle(upstream(), request().withMethod("GET").withPath("/x"))
            .getHttpResponse().get(5, TimeUnit.SECONDS);
        assertThat(recovered.getStatusCode(), is(200));
        assertThat(ForwardCircuitBreaker.getInstance().isOpen("upstream.example:8080"), is(false));
        assertThat(MetricsHelper.openCount(), is(0));
    }

    @Test
    public void shouldNotInterfereWhenCircuitBreakerDisabled() throws Exception {
        // given - breaker disabled (default), every request reaches the upstream regardless of failures
        Configuration configuration = Configuration.configuration();
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(failed(503));

        HttpForwardActionHandler handler = handlerWith(configuration);

        for (int i = 0; i < 10; i++) {
            HttpResponse result = handler.handle(upstream(), request().withMethod("GET").withPath("/x"))
                .getHttpResponse().get(5, TimeUnit.SECONDS);
            assertThat(result.getStatusCode(), is(503));
        }
        verify(mockHttpClient, times(10)).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
        assertThat(MetricsHelper.openCount(), is(0));
    }

    /** Reads the live open-circuit count exposed by Metrics (the gauge source). */
    private static final class MetricsHelper {
        static int openCount() {
            return org.mockserver.metrics.Metrics.getOpenUpstreamCircuitCount();
        }
    }
}
