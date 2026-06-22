package org.mockserver.model;

import java.util.Objects;

/**
 * Token usage for an LLM completion.
 *
 * <p>{@code inputTokens} and {@code outputTokens} are the baseline counts every
 * provider reports. The remaining fields are <strong>optional</strong> — when a
 * provider does not report them they stay {@code null} and behaviour is
 * unchanged (back-compatible):
 *
 * <ul>
 *   <li>{@code cachedInputTokens} — input tokens served from a prompt cache at a
 *       reduced rate. Maps to OpenAI {@code prompt_tokens_details.cached_tokens}
 *       and Anthropic {@code cache_read_input_tokens}. These are a subset of
 *       {@code inputTokens} (not additive) for both providers.</li>
 *   <li>{@code cacheCreationTokens} — input tokens written to a prompt cache at a
 *       premium rate. Maps to Anthropic {@code cache_creation_input_tokens}.</li>
 *   <li>{@code reasoningTokens} — "thinking"/reasoning tokens that are billed as
 *       output but not part of the visible completion. Maps to OpenAI
 *       {@code completion_tokens_details.reasoning_tokens}. These are a subset of
 *       {@code outputTokens} (not additive).</li>
 * </ul>
 *
 * <p>Cost dashboards that ignore these fields mis-bill cached and
 * reasoning-heavy workloads; surfacing them lets a downstream consumer apply the
 * provider's cache-read discount or attribute reasoning spend.
 */
public class Usage extends ObjectWithJsonToString {
    private int hashCode;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cachedInputTokens;
    private Integer cacheCreationTokens;
    private Integer reasoningTokens;

    public static Usage usage() {
        return new Usage();
    }

    public static Usage inputTokens(int inputTokens) {
        return new Usage().withInputTokens(inputTokens);
    }

    public static Usage outputTokens(int outputTokens) {
        return new Usage().withOutputTokens(outputTokens);
    }

    public Usage withInputTokens(Integer inputTokens) {
        if (inputTokens != null && inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must be >= 0");
        }
        this.inputTokens = inputTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Usage withOutputTokens(Integer outputTokens) {
        if (outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must be >= 0");
        }
        this.outputTokens = outputTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    /**
     * Input tokens served from a prompt cache (OpenAI
     * {@code prompt_tokens_details.cached_tokens} / Anthropic
     * {@code cache_read_input_tokens}). A subset of {@link #getInputTokens()}.
     */
    public Usage withCachedInputTokens(Integer cachedInputTokens) {
        if (cachedInputTokens != null && cachedInputTokens < 0) {
            throw new IllegalArgumentException("cachedInputTokens must be >= 0");
        }
        this.cachedInputTokens = cachedInputTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getCachedInputTokens() {
        return cachedInputTokens;
    }

    /**
     * Input tokens written to a prompt cache (Anthropic
     * {@code cache_creation_input_tokens}).
     */
    public Usage withCacheCreationTokens(Integer cacheCreationTokens) {
        if (cacheCreationTokens != null && cacheCreationTokens < 0) {
            throw new IllegalArgumentException("cacheCreationTokens must be >= 0");
        }
        this.cacheCreationTokens = cacheCreationTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    /**
     * Reasoning/thinking tokens billed as output but not part of the visible
     * completion (OpenAI {@code completion_tokens_details.reasoning_tokens}). A
     * subset of {@link #getOutputTokens()}.
     */
    public Usage withReasoningTokens(Integer reasoningTokens) {
        if (reasoningTokens != null && reasoningTokens < 0) {
            throw new IllegalArgumentException("reasoningTokens must be >= 0");
        }
        this.reasoningTokens = reasoningTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getReasoningTokens() {
        return reasoningTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        Usage usage = (Usage) o;
        return Objects.equals(inputTokens, usage.inputTokens) &&
            Objects.equals(outputTokens, usage.outputTokens) &&
            Objects.equals(cachedInputTokens, usage.cachedInputTokens) &&
            Objects.equals(cacheCreationTokens, usage.cacheCreationTokens) &&
            Objects.equals(reasoningTokens, usage.reasoningTokens);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(inputTokens, outputTokens, cachedInputTokens, cacheCreationTokens, reasoningTokens);
        }
        return hashCode;
    }
}
