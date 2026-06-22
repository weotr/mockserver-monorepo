package org.mockserver.netty.integration.mock;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.Expectation;
import org.mockserver.model.RateLimit;
import org.mockserver.netty.MockServer;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.RateLimit.rateLimit;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end coverage for the declarative {@code rateLimit} expectation clause over HTTP/1.1.
 * Uses a raw socket so the on-the-wire status line and {@code Retry-After} / {@code X-RateLimit-*}
 * headers can be asserted directly. The over-limit request returns a deterministic 429 instead of
 * the normal response; a within-limit request returns the normal response untouched.
 */
public class RateLimitIntegrationTest {

    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    private String sendRequestReadRawResponse(String path) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void shouldReturn429WithRateLimitHeadersWhenOverLimit() throws Exception {
        // given - an expectation with a fixed-window rate limit of 1 request per 60s
        Expectation expectation = when(request().withMethod("GET").withPath("/api/widgets"))
            .thenRespond(response().withStatusCode(200).withBody("ok"))
            .withRateLimit(
                rateLimit()
                    .withName("widgets-account")
                    .withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW)
                    .withLimit(1)
                    .withWindowMillis(60_000L)
                    .withRetryAfter("60")
            );
        mockServerClient.upsert(expectation);

        // when - first request is within the limit
        String first = sendRequestReadRawResponse("/api/widgets");
        // then - the normal response is returned untouched, with no rate-limit headers
        assertThat(first, containsString("200"));
        assertThat(first, containsString("ok"));
        assertThat(first, not(containsString("X-RateLimit-Limit")));

        // when - second request is over the limit
        String second = sendRequestReadRawResponse("/api/widgets");
        // then - a deterministic 429 with all rate-limit headers is returned instead
        assertThat(second, containsString("429"));
        assertThat(second, containsString("Retry-After: 60"));
        assertThat(second, containsString("X-RateLimit-Limit: 1"));
        assertThat(second, containsString("X-RateLimit-Remaining: 0"));
        assertThat(second, containsString("X-RateLimit-Reset:"));
        assertThat(second, containsString("rate_limit_exceeded"));
    }
}
