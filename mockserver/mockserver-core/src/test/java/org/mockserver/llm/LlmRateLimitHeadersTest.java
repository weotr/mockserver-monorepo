package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.Provider;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link LlmRateLimitHeaders} — verifies that each provider
 * returns the correct provider-specific header names, and that Gemini, Bedrock,
 * and Ollama return empty (the standard {@code Retry-After} header is owned by the
 * handler, not this helper).
 */
public class LlmRateLimitHeadersTest {

    private static final long NOW_EPOCH = 1_750_000_000L; // arbitrary fixed instant

    // ---- OpenAI / OpenAI Responses / Azure OpenAI ----

    @Test
    public void openAiLimitedReturnsAllProviderHeaders() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.OPENAI, 60, 0, 30L, NOW_EPOCH, true);

        assertThat(headers, hasEntry("x-ratelimit-limit-requests", "60"));
        assertThat(headers, hasEntry("x-ratelimit-remaining-requests", "0"));
        assertThat(headers, hasEntry("x-ratelimit-reset-requests", "30s"));
        // retry-after is owned by the handler, not this helper
        assertThat(headers, not(hasKey("retry-after")));
        assertThat(headers.size(), is(3));
    }

    @Test
    public void openAiNeverEmitsRetryAfter() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.OPENAI, 60, 55, 10L, NOW_EPOCH, false);

        assertThat(headers, hasEntry("x-ratelimit-limit-requests", "60"));
        assertThat(headers, hasEntry("x-ratelimit-remaining-requests", "55"));
        assertThat(headers, hasEntry("x-ratelimit-reset-requests", "10s"));
        assertThat(headers, not(hasKey("retry-after")));
    }

    @Test
    public void openAiResponsesUsesSameHeadersAsOpenAi() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.OPENAI_RESPONSES, 100, 99, 5L, NOW_EPOCH, false);

        assertThat(headers, hasKey("x-ratelimit-limit-requests"));
        assertThat(headers, hasKey("x-ratelimit-remaining-requests"));
        assertThat(headers, hasKey("x-ratelimit-reset-requests"));
    }

    @Test
    public void azureOpenAiUsesSameHeadersAsOpenAi() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.AZURE_OPENAI, 10, 0, 60L, NOW_EPOCH, true);

        assertThat(headers, hasEntry("x-ratelimit-limit-requests", "10"));
        assertThat(headers, not(hasKey("retry-after")));
    }

    // ---- Anthropic ----

    @Test
    public void anthropicLimitedReturnsCorrectHeaderNames() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.ANTHROPIC, 50, 0, 30L, NOW_EPOCH, true);

        assertThat(headers, hasEntry("anthropic-ratelimit-requests-limit", "50"));
        assertThat(headers, hasEntry("anthropic-ratelimit-requests-remaining", "0"));
        assertThat(headers, hasKey("anthropic-ratelimit-requests-reset"));
        // retry-after is owned by the handler, not this helper
        assertThat(headers, not(hasKey("retry-after")));
        // The reset value should be an RFC 3339 timestamp
        String reset = headers.get("anthropic-ratelimit-requests-reset");
        assertThat("should be RFC 3339 timestamp", reset, containsString("T"));
        assertThat("should end with Z", reset, endsWith("Z"));
    }

    @Test
    public void anthropicNotLimitedOmitsRetryAfter() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.ANTHROPIC, 50, 45, 10L, NOW_EPOCH, false);

        assertThat(headers, hasKey("anthropic-ratelimit-requests-limit"));
        assertThat(headers, hasKey("anthropic-ratelimit-requests-remaining"));
        assertThat(headers, hasKey("anthropic-ratelimit-requests-reset"));
        assertThat(headers, not(hasKey("retry-after")));
    }

    @Test
    public void anthropicResetTimestampIsNowPlusResetSeconds() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.ANTHROPIC, 10, 5, 60L, 1_750_000_000L, false);

        // 1_750_000_000 + 60 = 1_750_000_060 epoch seconds = 2025-06-15T11:14:20Z
        String reset = headers.get("anthropic-ratelimit-requests-reset");
        assertThat(reset, is(notNullValue()));
        // Verify it parses as a valid Instant and is 60s ahead
        java.time.Instant parsed = java.time.Instant.parse(reset);
        assertThat(parsed.getEpochSecond(), is(1_750_000_060L));
    }

    // ---- Gemini ----

    @Test
    public void geminiHasNoProviderSpecificHeaders() {
        // Gemini's only rate-limit signal is the standard Retry-After (added by the
        // handler), so this helper returns empty for it whether limited or not.
        assertThat(LlmRateLimitHeaders.headersFor(
            Provider.GEMINI, 60, 0, 30L, NOW_EPOCH, true).isEmpty(), is(true));
        assertThat(LlmRateLimitHeaders.headersFor(
            Provider.GEMINI, 60, 55, 30L, NOW_EPOCH, false).isEmpty(), is(true));
    }

    // ---- Bedrock ----

    @Test
    public void bedrockHasNoProviderSpecificHeaders() {
        // Bedrock's only rate-limit signal is the standard Retry-After (added by the
        // handler), so this helper returns empty for it whether limited or not.
        assertThat(LlmRateLimitHeaders.headersFor(
            Provider.BEDROCK, 10, 0, 15L, NOW_EPOCH, true).isEmpty(), is(true));
        assertThat(LlmRateLimitHeaders.headersFor(
            Provider.BEDROCK, 10, 5, 15L, NOW_EPOCH, false).isEmpty(), is(true));
    }

    // ---- Ollama ----

    @Test
    public void ollamaAlwaysReturnsEmpty() {
        assertThat(LlmRateLimitHeaders.headersFor(
            Provider.OLLAMA, 10, 0, 30L, NOW_EPOCH, true).isEmpty(), is(true));
        assertThat(LlmRateLimitHeaders.headersFor(
            Provider.OLLAMA, 10, 5, 30L, NOW_EPOCH, false).isEmpty(), is(true));
    }

    // ---- Null / edge cases ----

    @Test
    public void nullProviderReturnsEmpty() {
        assertThat(LlmRateLimitHeaders.headersFor(
            null, 10, 0, 30L, NOW_EPOCH, true).isEmpty(), is(true));
    }

    @Test
    public void nullInputsHandledGracefully() {
        // All numeric params null — should still produce an empty or partial map
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.OPENAI, null, null, null, NOW_EPOCH, true);
        assertThat(headers, not(hasKey("retry-after")));
        assertThat(headers.isEmpty(), is(true));
    }

    @Test
    public void openAiPartialNullsOmitMissingHeaders() {
        // Only limit set, rest null
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.OPENAI, 60, null, null, NOW_EPOCH, false);

        assertThat(headers, hasEntry("x-ratelimit-limit-requests", "60"));
        assertThat(headers, not(hasKey("x-ratelimit-remaining-requests")));
        assertThat(headers, not(hasKey("x-ratelimit-reset-requests")));
        assertThat(headers.size(), is(1));
    }

    @Test
    public void anthropicPartialNullsOmitMissingHeaders() {
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.ANTHROPIC, null, null, null, NOW_EPOCH, true);

        assertThat(headers.isEmpty(), is(true));
    }

    @Test
    public void limitedWithNullResetSecondsOmitsRetryAfter() {
        // OpenAI: limited=true but resetSeconds=null should NOT produce retry-after
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            Provider.OPENAI, 60, 0, null, NOW_EPOCH, true);

        assertThat(headers, not(hasKey("retry-after")));
        assertThat(headers, hasEntry("x-ratelimit-limit-requests", "60"));
        assertThat(headers, hasEntry("x-ratelimit-remaining-requests", "0"));
    }
}
