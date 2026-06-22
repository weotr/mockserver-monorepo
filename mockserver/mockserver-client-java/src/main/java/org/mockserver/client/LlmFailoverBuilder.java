package org.mockserver.client;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.List;

import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Builder for LLM provider failover/retry test scenarios.
 * <p>
 * Produces an ordered array of {@link Expectation}s that simulate a provider
 * returning failures for the first N attempts, then succeeding on subsequent
 * attempts. This lets you point a retry-capable LLM client (LiteLLM, Envoy
 * AI Gateway, an SDK's own retry config) at MockServer and deterministically
 * assert that failover/retry logic works.
 * <p>
 * The mechanism relies on MockServer's expectation matching order and
 * {@link Times} exhaustion: failure expectations are registered first with
 * limited Times, so they are matched and consumed before the unlimited
 * success expectation is reached.
 * <p>
 * Example:
 * <pre>
 * llmFailover()
 *     .withPath("/v1/chat/completions")
 *     .withProvider(Provider.OPENAI)
 *     .withModel("gpt-4o")
 *     .failWith(503)               // attempt 1 fails with 503
 *     .failWith(429)               // attempt 2 fails with 429
 *     .thenRespondWith(completion)  // attempt 3+ succeeds
 *     .build();                    // returns Expectation[]
 * </pre>
 */
public class LlmFailoverBuilder {

    private String path;
    private Provider provider;
    private String model;
    private final List<FailureSpec> failures = new ArrayList<>();
    private Completion successCompletion;

    private LlmFailoverBuilder() {
    }

    /**
     * Entry point for building an LLM failover scenario.
     *
     * @return a new LlmFailoverBuilder
     */
    public static LlmFailoverBuilder llmFailover() {
        return new LlmFailoverBuilder();
    }

    /**
     * Set the request path to match (e.g. {@code /v1/chat/completions}).
     *
     * @param path the path
     * @return this builder
     */
    public LlmFailoverBuilder withPath(String path) {
        this.path = path;
        return this;
    }

    /**
     * Set the LLM provider for the success response encoding.
     *
     * @param provider the provider
     * @return this builder
     */
    public LlmFailoverBuilder withProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Set the model name for the success response.
     *
     * @param model the model name
     * @return this builder
     */
    public LlmFailoverBuilder withModel(String model) {
        this.model = model;
        return this;
    }

    /**
     * Add a single failure attempt returning the given HTTP status code
     * with a default provider-plausible error body.
     *
     * @param statusCode the HTTP status code (e.g. 503, 429, 500)
     * @return this builder
     */
    public LlmFailoverBuilder failWith(int statusCode) {
        validateStatusCode(statusCode);
        failures.add(new FailureSpec(statusCode, null));
        return this;
    }

    private static void validateStatusCode(int statusCode) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 100 and 599, got " + statusCode);
        }
    }

    /**
     * Add a single failure attempt returning the given HTTP status code
     * with a custom error body.
     *
     * @param statusCode the HTTP status code (e.g. 503, 429, 500)
     * @param errorBody  the response body to return
     * @return this builder
     */
    public LlmFailoverBuilder failWith(int statusCode, String errorBody) {
        validateStatusCode(statusCode);
        failures.add(new FailureSpec(statusCode, errorBody));
        return this;
    }

    /**
     * Add {@code count} consecutive failure attempts returning the given
     * HTTP status code with a default provider-plausible error body.
     *
     * @param statusCode the HTTP status code
     * @param count      number of consecutive failures
     * @return this builder
     */
    public LlmFailoverBuilder failWith(int statusCode, int count) {
        validateStatusCode(statusCode);
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1, got " + count);
        }
        for (int i = 0; i < count; i++) {
            failures.add(new FailureSpec(statusCode, null));
        }
        return this;
    }

    /**
     * Set the completion to return after all failures are exhausted.
     *
     * @param completion the success completion
     * @return this builder
     */
    public LlmFailoverBuilder thenRespondWith(Completion completion) {
        this.successCompletion = completion;
        return this;
    }

    /**
     * Build and register all expectations with the MockServerClient.
     *
     * @param client the MockServerClient
     * @return the created expectations
     */
    public Expectation[] applyTo(MockServerClient client) {
        Expectation[] expectations = build();
        return client.upsert(expectations);
    }

    /**
     * Build all expectations without registering them.
     * <p>
     * Returns an ordered array of failure expectations followed by a single
     * success expectation with {@code Times.unlimited()}. Consecutive failures
     * with the same status code (and body) are coalesced into one expectation with
     * {@code Times.exactly(count)}; otherwise each failure is its own expectation
     * with {@code Times.exactly(1)}. Registration order ensures failures are matched
     * first and consumed before the success expectation.
     *
     * @return the array of expectations
     */
    public Expectation[] build() {
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException("Path must be set");
        }
        if (provider == null) {
            throw new IllegalStateException("Provider must be set");
        }
        if (failures.isEmpty()) {
            throw new IllegalStateException("At least one failure must be defined");
        }
        if (successCompletion == null) {
            throw new IllegalStateException("Success completion must be set via thenRespondWith()");
        }

        // Coalesce consecutive failures with the same status+body into
        // a single expectation with Times.exactly(count) for efficiency.
        List<CoalescedFailure> coalesced = coalesceFailures();

        Expectation[] expectations = new Expectation[coalesced.size() + 1];

        // Failure expectations (limited Times, matched and consumed first)
        for (int i = 0; i < coalesced.size(); i++) {
            CoalescedFailure cf = coalesced.get(i);
            String body = cf.errorBody != null ? cf.errorBody : defaultErrorBody(cf.statusCode);

            HttpResponse errorResponse = response()
                .withStatusCode(cf.statusCode)
                .withHeader("Content-Type", "application/json")
                .withBody(body);

            expectations[i] = Expectation.when(
                request().withMethod("POST").withPath(path),
                Times.exactly(cf.count),
                TimeToLive.unlimited()
            ).thenRespond(errorResponse);
        }

        // Success expectation (unlimited Times, matched after failures exhausted)
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(provider)
            .withModel(model)
            .withCompletion(successCompletion);

        expectations[coalesced.size()] = Expectation.when(
            request().withMethod("POST").withPath(path),
            Times.unlimited(),
            TimeToLive.unlimited()
        ).thenRespondWithLlm(llmResponse);

        return expectations;
    }

    /**
     * Returns the total number of failure attempts configured.
     *
     * @return the failure count
     */
    public int getFailureCount() {
        return failures.size();
    }

    /**
     * Coalesce consecutive failures with the same status code and error body
     * into a single entry with a count, so we produce fewer expectations.
     */
    private List<CoalescedFailure> coalesceFailures() {
        List<CoalescedFailure> result = new ArrayList<>();
        for (FailureSpec spec : failures) {
            if (!result.isEmpty()) {
                CoalescedFailure last = result.get(result.size() - 1);
                if (last.statusCode == spec.statusCode
                    && java.util.Objects.equals(last.errorBody, spec.errorBody)) {
                    last.count++;
                    continue;
                }
            }
            result.add(new CoalescedFailure(spec.statusCode, spec.errorBody, 1));
        }
        return result;
    }

    /**
     * Generate a provider-plausible JSON error body for the given HTTP status.
     */
    static String defaultErrorBody(int statusCode) {
        String type;
        String message;
        switch (statusCode) {
            case 429:
                type = "rate_limit_error";
                message = "Rate limit exceeded. Please retry after a brief wait.";
                break;
            case 500:
                type = "internal_server_error";
                message = "An internal error occurred. Please retry your request.";
                break;
            case 502:
                type = "bad_gateway";
                message = "Bad gateway. The upstream server returned an invalid response.";
                break;
            case 503:
                type = "service_unavailable";
                message = "The service is temporarily overloaded. Please retry later.";
                break;
            default:
                type = "error";
                message = "Request failed with status " + statusCode;
                break;
        }
        return "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    private static class FailureSpec {
        final int statusCode;
        final String errorBody;

        FailureSpec(int statusCode, String errorBody) {
            this.statusCode = statusCode;
            this.errorBody = errorBody;
        }
    }

    private static class CoalescedFailure {
        final int statusCode;
        final String errorBody;
        int count;

        CoalescedFailure(int statusCode, String errorBody, int count) {
            this.statusCode = statusCode;
            this.errorBody = errorBody;
            this.count = count;
        }
    }
}
