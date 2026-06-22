package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;
import static org.mockserver.model.HttpResponse.response;

public class ForwardRetryPolicyTest {

    private static HttpResponse get(CompletableFuture<HttpResponse> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void shouldClassifyIdempotentMethods() {
        assertThat(ForwardRetryPolicy.isIdempotent("GET"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("get"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("HEAD"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("PUT"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("DELETE"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("OPTIONS"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("TRACE"), is(true));
        assertThat(ForwardRetryPolicy.isIdempotent("POST"), is(false));
        assertThat(ForwardRetryPolicy.isIdempotent("PATCH"), is(false));
        assertThat(ForwardRetryPolicy.isIdempotent(null), is(false));
        assertThat(ForwardRetryPolicy.isIdempotent(""), is(false));
    }

    @Test
    public void shouldClassifyTransientFailures() {
        assertThat(ForwardRetryPolicy.isTransientFailure(response().withStatusCode(502), null), is(true));
        assertThat(ForwardRetryPolicy.isTransientFailure(response().withStatusCode(503), null), is(true));
        assertThat(ForwardRetryPolicy.isTransientFailure(response().withStatusCode(504), null), is(true));
        assertThat(ForwardRetryPolicy.isTransientFailure(response().withStatusCode(500), null), is(false));
        assertThat(ForwardRetryPolicy.isTransientFailure(response().withStatusCode(200), null), is(false));
        assertThat(ForwardRetryPolicy.isTransientFailure(null, new RuntimeException("boom")), is(true));
    }

    @Test
    public void shouldNotRetryWhenMaxRetriesIsZero() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response().withStatusCode(503));
        };

        HttpResponse result = get(ForwardRetryPolicy.execute("GET", 0, 0, attempt));

        assertThat(calls.get(), is(1));
        assertThat(result.getStatusCode(), is(503));
    }

    @Test
    public void shouldNotRetryNonIdempotentMethod() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response().withStatusCode(503));
        };

        HttpResponse result = get(ForwardRetryPolicy.execute("POST", 3, 0, attempt));

        assertThat(calls.get(), is(1));
        assertThat(result.getStatusCode(), is(503));
    }

    @Test
    public void shouldSucceedAfterTransientFailures() throws Exception {
        // given - first 2 attempts fail with 503, third succeeds with 200 (maxRetries=3)
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            int call = calls.incrementAndGet();
            return CompletableFuture.completedFuture(response().withStatusCode(call < 3 ? 503 : 200));
        };

        // when
        HttpResponse result = get(ForwardRetryPolicy.execute("GET", 3, 0, attempt));

        // then
        assertThat(calls.get(), is(3));
        assertThat(result.getStatusCode(), is(200));
    }

    @Test
    public void shouldRetryOnExceptionThenSucceed() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                CompletableFuture<HttpResponse> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("connection refused"));
                return failed;
            }
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        };

        HttpResponse result = get(ForwardRetryPolicy.execute("GET", 2, 0, attempt));

        assertThat(calls.get(), is(2));
        assertThat(result.getStatusCode(), is(200));
    }

    @Test
    public void shouldExhaustRetriesAndReturnLastFailure() throws Exception {
        // given - every attempt fails with 503, maxRetries=2 => 3 attempts total
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response().withStatusCode(503));
        };

        HttpResponse result = get(ForwardRetryPolicy.execute("GET", 2, 0, attempt));

        assertThat(calls.get(), is(3));
        assertThat(result.getStatusCode(), is(503));
    }

    @Test
    public void shouldPropagateExceptionWhenRetriesExhausted() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            calls.incrementAndGet();
            CompletableFuture<HttpResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("boom"));
            return failed;
        };

        try {
            get(ForwardRetryPolicy.execute("GET", 1, 0, attempt));
            fail("expected ExecutionException");
        } catch (ExecutionException e) {
            assertThat(e.getCause().getMessage(), containsString("boom"));
        }
        assertThat(calls.get(), is(2));
    }

    @Test
    public void shouldApplyBackoffBetweenAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            int call = calls.incrementAndGet();
            return CompletableFuture.completedFuture(response().withStatusCode(call < 2 ? 503 : 200));
        };

        long start = System.currentTimeMillis();
        HttpResponse result = get(ForwardRetryPolicy.execute("GET", 2, 50, attempt));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.getStatusCode(), is(200));
        // first retry waits backoff*1 = 50ms
        assertThat(elapsed, is(greaterThanOrEqualTo(40L)));
    }

    @Test
    public void shouldNotRetrySuccessfulResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<CompletableFuture<HttpResponse>> attempt = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        };

        HttpResponse result = get(ForwardRetryPolicy.execute("GET", 3, 0, attempt));

        assertThat(calls.get(), is(1));
        assertThat(result.getStatusCode(), is(200));
    }
}
