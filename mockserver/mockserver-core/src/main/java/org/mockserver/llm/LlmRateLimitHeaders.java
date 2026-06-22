package org.mockserver.llm;

import org.mockserver.model.Provider;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, deterministic helper that produces the <strong>provider-specific</strong>
 * rate-limit HTTP headers real LLM providers send. Client SDKs (e.g. the OpenAI
 * Python SDK, Anthropic SDK) read these headers to drive retry/backoff logic, so
 * emitting them faithfully allows MockServer to exercise that logic against a mock.
 *
 * <p>The standard {@code Retry-After} header is intentionally <em>not</em> produced
 * here — it is a generic HTTP header (not provider-specific) and is owned solely by
 * {@code HttpLlmResponseActionHandler.applyRateLimitHeaders(...)}, which emits it
 * for every provider (including those with no provider-specific headers, such as
 * Gemini, Bedrock, and Ollama). Keeping {@code Retry-After} in one place avoids a
 * duplicate header on the wire.
 *
 * <h3>Provider header reference</h3>
 * <ul>
 *   <li><strong>OPENAI / OPENAI_RESPONSES / AZURE_OPENAI</strong> (source: OpenAI docs
 *       "Rate limits" page) — {@code x-ratelimit-limit-requests},
 *       {@code x-ratelimit-remaining-requests}, {@code x-ratelimit-reset-requests}
 *       (duration, e.g. "6s").</li>
 *   <li><strong>ANTHROPIC</strong> (source: Anthropic docs "Rate limits" page) —
 *       {@code anthropic-ratelimit-requests-limit},
 *       {@code anthropic-ratelimit-requests-remaining},
 *       {@code anthropic-ratelimit-requests-reset} (RFC 3339 timestamp).</li>
 *   <li><strong>GEMINI / BEDROCK</strong> — no provider-specific rate-limit headers;
 *       on a 429 only the standard {@code Retry-After} header (added by the handler)
 *       is exposed.</li>
 *   <li><strong>OLLAMA</strong> — none. Ollama is a local inference engine with
 *       no rate-limit concept.</li>
 * </ul>
 *
 * <p>All methods are static, deterministic, and pure (no clocks, no randomness
 * inside — the caller passes {@code resetSeconds} and the current epoch second
 * for RFC 3339 timestamps).
 */
public final class LlmRateLimitHeaders {

    private LlmRateLimitHeaders() {
    }

    /**
     * Produce provider-specific rate-limit headers (excluding {@code Retry-After},
     * which the caller emits).
     *
     * @param provider          the LLM provider
     * @param requestLimit      quota limit (requests per window); may be {@code null}
     * @param requestRemaining  requests remaining in the window; may be {@code null}
     * @param resetSeconds      seconds until the window resets; may be {@code null}
     * @param nowEpochSecond    current epoch second (for Anthropic RFC 3339 reset timestamp)
     * @param limited           {@code true} when this is a rate-limit error (429);
     *                          {@code false} for a successful response with quota info
     * @return an insertion-ordered map of header-name to header-value; empty if
     *         the provider has no provider-specific rate-limit headers
     *         (Gemini, Bedrock, Ollama)
     */
    public static Map<String, String> headersFor(
        Provider provider,
        Integer requestLimit,
        Integer requestRemaining,
        Long resetSeconds,
        long nowEpochSecond,
        boolean limited
    ) {
        if (provider == null) {
            return Collections.emptyMap();
        }
        switch (provider) {
            case OPENAI:
            case OPENAI_RESPONSES:
            case AZURE_OPENAI:
                return openAiHeaders(requestLimit, requestRemaining, resetSeconds);
            case ANTHROPIC:
                return anthropicHeaders(requestLimit, requestRemaining, resetSeconds, nowEpochSecond);
            case GEMINI:
            case BEDROCK:
            case OLLAMA:
            default:
                return Collections.emptyMap();
        }
    }

    /**
     * OpenAI-family headers (OpenAI, OpenAI Responses, Azure OpenAI).
     * <p>
     * Example real headers from the OpenAI API:
     * <pre>
     * x-ratelimit-limit-requests: 60
     * x-ratelimit-remaining-requests: 59
     * x-ratelimit-reset-requests: 1s
     * </pre>
     */
    private static Map<String, String> openAiHeaders(
        Integer requestLimit, Integer requestRemaining, Long resetSeconds
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (requestLimit != null) {
            headers.put("x-ratelimit-limit-requests", String.valueOf(requestLimit));
        }
        if (requestRemaining != null) {
            headers.put("x-ratelimit-remaining-requests", String.valueOf(requestRemaining));
        }
        if (resetSeconds != null) {
            headers.put("x-ratelimit-reset-requests", resetSeconds + "s");
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Anthropic headers.
     * <p>
     * Example real headers from the Anthropic API:
     * <pre>
     * anthropic-ratelimit-requests-limit: 60
     * anthropic-ratelimit-requests-remaining: 59
     * anthropic-ratelimit-requests-reset: 2025-01-01T00:01:00Z
     * </pre>
     */
    private static Map<String, String> anthropicHeaders(
        Integer requestLimit, Integer requestRemaining, Long resetSeconds, long nowEpochSecond
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (requestLimit != null) {
            headers.put("anthropic-ratelimit-requests-limit", String.valueOf(requestLimit));
        }
        if (requestRemaining != null) {
            headers.put("anthropic-ratelimit-requests-remaining", String.valueOf(requestRemaining));
        }
        if (resetSeconds != null) {
            String resetTimestamp = Instant.ofEpochSecond(nowEpochSecond + resetSeconds)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
            headers.put("anthropic-ratelimit-requests-reset", resetTimestamp);
        }
        return Collections.unmodifiableMap(headers);
    }
}
