package org.mockserver.mock.action.http;

import org.mockserver.model.HttpResponse;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Stateless retry logic for forwarded/proxied requests. Re-issues a request to its upstream up to
 * {@code maxRetries} times when the previous attempt produced a <b>transient</b> failure — a
 * connection-level exception, or an upstream response of 502/503/504 — provided the HTTP method is
 * <b>idempotent</b>. Non-idempotent methods (POST, PATCH) are never retried, so a request is never
 * silently executed twice.
 *
 * <p>The whole policy is inert when {@code maxRetries <= 0}: {@link #execute} simply returns the
 * first attempt's future, preserving the historical "forward exactly once" behaviour.
 *
 * <p>Retries are chained asynchronously off the supplied future (never blocking the event loop). A
 * linear back-off ({@code backoffMillis * attemptNumber}) is applied between attempts via
 * {@link CompletableFuture#delayedExecutor}.
 */
public final class ForwardRetryPolicy {

    /**
     * HTTP methods that are safe to retry because re-issuing them has the same effect as a single
     * call (RFC 7231 idempotent methods). POST and PATCH are deliberately excluded.
     */
    static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE");

    /** Upstream status codes treated as transient (worth retrying). */
    static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(502, 503, 504);

    private ForwardRetryPolicy() {
    }

    /**
     * Whether a request using the given HTTP method may be retried.
     *
     * @param method the request method (case-insensitive); null/blank is treated as non-idempotent
     */
    public static boolean isIdempotent(String method) {
        return method != null && IDEMPOTENT_METHODS.contains(method.trim().toUpperCase());
    }

    /**
     * Whether a completed attempt (response or throwable) should be retried. A non-null throwable is
     * always transient; a non-null response is transient only when its status code is 502/503/504.
     */
    public static boolean isTransientFailure(HttpResponse response, Throwable throwable) {
        if (throwable != null) {
            return true;
        }
        return response != null
            && response.getStatusCode() != null
            && RETRYABLE_STATUS_CODES.contains(response.getStatusCode());
    }

    /**
     * Run {@code attempt} with retry. When {@code maxRetries <= 0} or the method is non-idempotent
     * the first attempt's future is returned unchanged. Otherwise a transient failure (exception or
     * 502/503/504) triggers up to {@code maxRetries} further attempts with linear back-off; the
     * future from the last attempt is returned once a non-transient result is produced or the retry
     * budget is exhausted.
     *
     * @param method        the request HTTP method (drives the idempotency check)
     * @param maxRetries    maximum number of retries (0 disables retry)
     * @param backoffMillis base linear back-off between attempts in milliseconds
     * @param attempt       supplies a fresh attempt future each time it is invoked
     * @return a future completing with the final attempt's response
     */
    public static CompletableFuture<HttpResponse> execute(
        String method,
        int maxRetries,
        long backoffMillis,
        Supplier<CompletableFuture<HttpResponse>> attempt
    ) {
        CompletableFuture<HttpResponse> first = attempt.get();
        if (maxRetries <= 0 || !isIdempotent(method)) {
            return first;
        }
        return attemptWithRetry(first, maxRetries, backoffMillis, 1, attempt);
    }

    private static CompletableFuture<HttpResponse> attemptWithRetry(
        CompletableFuture<HttpResponse> current,
        int maxRetries,
        long backoffMillis,
        int attemptNumber,
        Supplier<CompletableFuture<HttpResponse>> attempt
    ) {
        return current
            .handle((response, throwable) -> new Outcome(response, throwable))
            .thenCompose(outcome -> {
                if (attemptNumber > maxRetries || !isTransientFailure(outcome.response, outcome.throwable)) {
                    // Out of retries or not transient — surface the outcome unchanged.
                    if (outcome.throwable != null) {
                        CompletableFuture<HttpResponse> failed = new CompletableFuture<>();
                        failed.completeExceptionally(outcome.throwable);
                        return failed;
                    }
                    return CompletableFuture.completedFuture(outcome.response);
                }
                long delay = Math.max(0L, backoffMillis) * attemptNumber;
                Supplier<CompletableFuture<HttpResponse>> nextAttempt = () -> {
                    try {
                        return attempt.get();
                    } catch (Throwable t) {
                        CompletableFuture<HttpResponse> failed = new CompletableFuture<>();
                        failed.completeExceptionally(t instanceof CompletionException && t.getCause() != null ? t.getCause() : t);
                        return failed;
                    }
                };
                CompletableFuture<CompletableFuture<HttpResponse>> scheduled = delay > 0
                    ? CompletableFuture.supplyAsync(nextAttempt, CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS))
                    : CompletableFuture.completedFuture(nextAttempt.get());
                return scheduled.thenCompose(next -> attemptWithRetry(next, maxRetries, backoffMillis, attemptNumber + 1, attempt));
            });
    }

    /** Captures the (response, throwable) pair from a single completed attempt. */
    private static final class Outcome {
        final HttpResponse response;
        final Throwable throwable;

        Outcome(HttpResponse response, Throwable throwable) {
            this.response = response;
            this.throwable = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
        }
    }
}
