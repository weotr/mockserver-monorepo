package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LlmChaosProfile;
import org.mockserver.model.Provider;
import org.mockserver.model.SseEvent;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class HttpLlmResponseActionHandlerChaosTest {

    private final HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());

    private static HttpLlmResponse withChaos(LlmChaosProfile chaos) {
        return HttpLlmResponse.llmResponse().withProvider(Provider.OPENAI).withChaos(chaos);
    }

    @Test
    public void noChaosProfileProducesNoError() {
        assertThat(handler.chaosErrorResponseOrNull(HttpLlmResponse.llmResponse().withProvider(Provider.OPENAI)),
            is(nullValue()));
    }

    @Test
    public void errorStatusWithoutProbabilityAlwaysFires() {
        HttpResponse response = handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorStatus(429).withRetryAfter("30")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getFirstHeader("Retry-After"), is("30"));
        // OpenAI 429 → OpenAI-correct rate-limit error envelope (not the generic body)
        assertThat(response.getBodyAsString(), containsString("rate_limit_exceeded"));
    }

    @Test
    public void zeroProbabilityNeverFires() {
        assertThat(handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorStatus(500).withErrorProbability(0.0))), is(nullValue()));
    }

    @Test
    public void probabilityOneAlwaysFires() {
        assertThat(handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorStatus(529).withErrorProbability(1.0))), is(notNullValue()));
    }

    @Test
    public void seededFractionalProbabilityIsDeterministic() {
        LlmChaosProfile chaos = LlmChaosProfile.llmChaosProfile().withErrorStatus(500).withErrorProbability(0.5).withSeed(42L);
        boolean first = handler.chaosErrorResponseOrNull(withChaos(chaos)) != null;
        boolean second = handler.chaosErrorResponseOrNull(withChaos(chaos)) != null;
        assertThat(first, is(second));
    }

    private static List<SseEvent> events(int n) {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add(SseEvent.sseEvent().withData("{\"i\":" + i + "}"));
        }
        return events;
    }

    @Test
    public void truncationKeepsLeadingFraction() {
        List<SseEvent> result = handler.applyStreamingChaos(events(10),
            LlmChaosProfile.llmChaosProfile().withTruncateMode(LlmChaosProfile.TruncateMode.MID_STREAM).withTruncateAtFraction(0.3));
        assertThat(result.size(), is(3));
    }

    @Test
    public void truncationDefaultsToHalf() {
        List<SseEvent> result = handler.applyStreamingChaos(events(10),
            LlmChaosProfile.llmChaosProfile().withTruncateMode(LlmChaosProfile.TruncateMode.MID_STREAM));
        assertThat(result.size(), is(5));
    }

    @Test
    public void malformedSseAppendsBrokenChunk() {
        List<SseEvent> result = handler.applyStreamingChaos(events(2),
            LlmChaosProfile.llmChaosProfile().withMalformedSse(true));
        assertThat(result.size(), is(3));
        assertThat(result.get(result.size() - 1).getData(), is("{\"malformed\":true"));
    }

    @Test
    public void truncateThenMalformedCombine() {
        List<SseEvent> result = handler.applyStreamingChaos(events(10),
            LlmChaosProfile.llmChaosProfile()
                .withTruncateMode(LlmChaosProfile.TruncateMode.MID_STREAM).withTruncateAtFraction(0.5)
                .withMalformedSse(true));
        // 5 kept + 1 malformed
        assertThat(result.size(), is(6));
    }

    @Test
    public void noStreamingChaosReturnsInputUnchanged() {
        List<SseEvent> input = events(4);
        assertThat(handler.applyStreamingChaos(input, LlmChaosProfile.llmChaosProfile()).size(), is(4));
        assertThat(handler.applyStreamingChaos(input, null).size(), is(4));
    }

    // --- Provider-correct rate-limit headers ---

    @Test
    public void chaosErrorForOpenAiCarriesOpenAiRateLimitHeaders() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile()
                    .withErrorStatus(429)
                    .withRetryAfter("30")
                    .withQuotaName("openai-acct")
                    .withQuotaLimit(10)
                    .withQuotaWindowMillis(60_000L)));
        // first call is within quota, so the probabilistic error fires (errorStatus=429, no errorProbability = always)
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getFirstHeader("x-ratelimit-limit-requests"), is("10"));
        assertThat(response.getFirstHeader("x-ratelimit-reset-requests"), is("60s"));
    }

    @Test
    public void chaosErrorForAnthropicCarriesAnthropicRateLimitHeaders() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withChaos(LlmChaosProfile.llmChaosProfile()
                    .withErrorStatus(429)
                    .withQuotaName("anthropic-acct")
                    .withQuotaLimit(5)
                    .withQuotaWindowMillis(30_000L)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getFirstHeader("anthropic-ratelimit-requests-limit"), is("5"));
        // Anthropic uses RFC 3339 timestamps for reset
        assertThat(response.getFirstHeader("anthropic-ratelimit-requests-reset"), containsString("T"));
    }

    @Test
    public void quotaExceededForOpenAiCarriesOpenAiHeaders() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withProvider(Provider.OPENAI)
            .withChaos(LlmChaosProfile.llmChaosProfile()
                .withQuotaName("openai-quota-test")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(60_000L));
        // first call is within quota
        handler.chaosErrorResponseOrNull(llmResponse);
        // second call exceeds quota
        HttpResponse response = handler.chaosErrorResponseOrNull(llmResponse);
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getFirstHeader("x-ratelimit-limit-requests"), is("1"));
        assertThat(response.getFirstHeader("x-ratelimit-remaining-requests"), is("0"));
        assertThat(response.getFirstHeader("x-ratelimit-reset-requests"), is("60s"));
        assertThat(response.getFirstHeader("retry-after"), is("60"));
    }

    @Test
    public void quotaExceededForAnthropicCarriesAnthropicHeaders() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withChaos(LlmChaosProfile.llmChaosProfile()
                .withQuotaName("anthropic-quota-test")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(30_000L));
        handler.chaosErrorResponseOrNull(llmResponse);
        HttpResponse response = handler.chaosErrorResponseOrNull(llmResponse);
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getFirstHeader("anthropic-ratelimit-requests-limit"), is("1"));
        assertThat(response.getFirstHeader("anthropic-ratelimit-requests-remaining"), is("0"));
        assertThat(response.getFirstHeader("anthropic-ratelimit-requests-reset"), containsString("T"));
        assertThat(response.getFirstHeader("retry-after"), is("30"));
    }

    @Test
    public void ollamaCarriesNoRateLimitHeaders() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OLLAMA)
                .withChaos(LlmChaosProfile.llmChaosProfile()
                    .withErrorStatus(429)
                    .withRetryAfter("10")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        // Ollama should NOT have provider-specific rate-limit headers
        assertThat(response.getFirstHeader("x-ratelimit-limit-requests"), is(""));
        assertThat(response.getFirstHeader("anthropic-ratelimit-requests-limit"), is(""));
        // But the explicit Retry-After from the chaos profile is still present
        assertThat(response.getFirstHeader("Retry-After"), is("10"));
    }

    @Test
    public void successfulResponseWithQuotaCarriesLimitHeaders() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withProvider(Provider.OPENAI)
            .withCompletion(org.mockserver.model.Completion.completion().withText("Hello"))
            .withChaos(LlmChaosProfile.llmChaosProfile()
                .withQuotaName("success-quota-test")
                .withQuotaLimit(10)
                .withQuotaWindowMillis(60_000L));
        HttpResponse response = handler.handle(llmResponse, org.mockserver.model.HttpRequest.request().withPath("/v1/chat/completions"));
        assertThat(response.getStatusCode(), is(200));
        // Should carry rate-limit headers even on success when quota is configured
        assertThat(response.getFirstHeader("x-ratelimit-limit-requests"), is("10"));
        assertThat(response.getFirstHeader("x-ratelimit-reset-requests"), is("60s"));
        // Not limited, so no retry-after
        assertThat(response.getFirstHeader("retry-after"), is(""));
    }

    // --- Provider-specific error bodies ---

    @Test
    public void anthropicOverloadEmitsOverloadedErrorBody() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorStatus(529).withErrorProbability(1.0)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(529));
        assertThat(response.getBodyAsString(), containsString("\"type\":\"error\""));
        assertThat(response.getBodyAsString(), containsString("\"overloaded_error\""));
    }

    @Test
    public void anthropicRateLimitEmitsRateLimitErrorBody() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorStatus(429)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getBodyAsString(), containsString("\"rate_limit_error\""));
    }

    @Test
    public void openAiServerErrorEmitsOpenAiEnvelope() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorStatus(503).withErrorProbability(1.0)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(503));
        assertThat(response.getBodyAsString(), containsString("\"server_error\""));
        assertThat(response.getBodyAsString(), containsString("\"code\":503"));
    }

    @Test
    public void openAiRateLimitEmitsRateLimitExceededEnvelope() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorStatus(429)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getBodyAsString(), containsString("\"rate_limit_exceeded\""));
    }

    @Test
    public void unknownProviderFallsBackToGenericBody() {
        // a null provider has no provider-specific shape, so the generic body is used
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorStatus(500).withErrorProbability(1.0)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), containsString("\"chaos_injected\""));
    }

    @Test
    public void successfulResponseWithoutChaosHasNoRateLimitHeaders() {
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withProvider(Provider.OPENAI)
            .withCompletion(org.mockserver.model.Completion.completion().withText("Hello"));
        HttpResponse response = handler.handle(llmResponse, org.mockserver.model.HttpRequest.request().withPath("/v1/chat/completions"));
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("x-ratelimit-limit-requests"), is(""));
        assertThat(response.getFirstHeader("retry-after"), is(""));
    }

    // --- Provider-specific overload / error bodies (errorKind) ---

    @Test
    public void anthropicOverloadErrorKindYields529OverloadedBody() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorKind("OVERLOAD")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(529));
        assertThat(response.getBodyAsString(), containsString("overloaded_error"));
    }

    @Test
    public void anthropicErrorKindIsCaseInsensitive() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorKind("overload")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(529));
        assertThat(response.getBodyAsString(), containsString("overloaded_error"));
    }

    @Test
    public void openAiRateLimitErrorKindYieldsOpenAiEnvelope() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorKind("RATE_LIMIT")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getBodyAsString(), containsString("rate_limit_exceeded"));
    }

    @Test
    public void openAiOverloadErrorKindYields503ServerError() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorKind("OVERLOAD")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(503));
        assertThat(response.getBodyAsString(), containsString("server_error"));
    }

    @Test
    public void explicitErrorStatusOverridesProviderNaturalStatusButKeepsBody() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorKind("OVERLOAD").withErrorStatus(503)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(503));
        assertThat(response.getBodyAsString(), containsString("overloaded_error"));
    }

    @Test
    public void unknownProviderWithErrorKindFallsBackToGenericBody() {
        // null provider -> LlmErrorBody returns null, so the generic chaos body is used.
        // Need an errorStatus because the generic fallback has no natural status.
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorKind("OVERLOAD").withErrorStatus(503)));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(503));
        assertThat(response.getBodyAsString(), containsString("chaos_injected"));
    }

    @Test
    public void unrecognisedErrorKindWithoutStatusDoesNotFire() {
        // An unrecognised kind parses to null; with no errorStatus there is nothing to inject.
        assertThat(handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorKind("bogus"))), is(nullValue()));
    }

    @Test
    public void quotaBreachWithErrorKindYieldsProviderBody() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withChaos(LlmChaosProfile.llmChaosProfile()
                .withErrorKind("RATE_LIMIT")
                .withQuotaName("anthropic-kind-quota")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(30_000L));
        handler.chaosErrorResponseOrNull(llmResponse); // within quota
        HttpResponse response = handler.chaosErrorResponseOrNull(llmResponse); // exceeds quota
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getBodyAsString(), containsString("rate_limit_error"));
    }

    @Test
    public void quotaBreachWithoutErrorKindUsesProviderRateLimitBody() {
        // No errorKind: a 429 quota breach for a known provider still emits the
        // provider-correct rate-limit body (status-derived), not the generic body.
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withChaos(LlmChaosProfile.llmChaosProfile()
                .withQuotaName("anthropic-generic-quota")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(30_000L));
        handler.chaosErrorResponseOrNull(llmResponse);
        HttpResponse response = handler.chaosErrorResponseOrNull(llmResponse);
        assertThat(response, is(notNullValue()));
        assertThat(response.getBodyAsString(), containsString("rate_limit_error"));
    }

    @Test
    public void quotaBreachWithoutErrorKindOrProviderKeepsGenericBody() {
        // Unknown/null provider has no provider-specific shape, so the generic quota
        // body (with its distinct quota_exceeded error type) is preserved.
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
            .withChaos(LlmChaosProfile.llmChaosProfile()
                .withQuotaName("no-provider-generic-quota")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(30_000L));
        handler.chaosErrorResponseOrNull(llmResponse);
        HttpResponse response = handler.chaosErrorResponseOrNull(llmResponse);
        assertThat(response, is(notNullValue()));
        assertThat(response.getBodyAsString(), containsString("quota_exceeded"));
    }
}
