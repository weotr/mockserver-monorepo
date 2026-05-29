package org.mockserver.llm.client;

import com.google.common.collect.ImmutableMap;
import org.mockserver.model.Provider;

import java.util.Map;

/**
 * Declares a real LLM backend that MockServer can call as a <em>client</em>
 * (the opposite direction to the mock codecs, which build outbound mock
 * <em>responses</em>). Used only by runtime-LLM features (drift detection,
 * semantic matching) — never on the deterministic assertion/matching path.
 * <p>
 * Reuses the {@link Provider} enum as the "type", mirroring the codec registry
 * so the provider taxonomy stays the single source of truth. Most fields are
 * optional: {@code baseUrl} and {@code model} default per provider in the
 * per-provider {@link LlmClient}, so enabling a backend is usually just a
 * type plus a key (Ollama needs neither).
 * <p>
 * Immutable: {@code headers} is defensively copied to an unmodifiable map in the
 * compact constructor. {@code apiKey} is a secret and is redacted by
 * {@link #toString()}.
 */
public record LlmBackend(
    String name,                 // optional — required only for named (layer 3) backends
    Provider provider,           // the "type" — reuses the existing enum
    String baseUrl,              // optional — defaults per provider
    String apiKey,               // secret — redacted in toString()
    String model,                // optional — provider default
    Map<String, String> headers, // escape hatch for odd setups
    Long timeoutMillis           // optional — service applies a conservative default
) {
    public LlmBackend {
        headers = headers == null ? ImmutableMap.of() : ImmutableMap.copyOf(headers);
    }

    /**
     * Convenience for the common case: a provider type and an API key, all other
     * fields defaulted.
     */
    public static LlmBackend of(Provider provider, String apiKey) {
        return new LlmBackend(null, provider, null, apiKey, null, null, null);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String toString() {
        // apiKey is a secret — never emit it, even in logs or config dumps.
        return "LlmBackend{" +
            "name=" + name +
            ", provider=" + provider +
            ", baseUrl=" + baseUrl +
            ", apiKey=" + (hasApiKey() ? "***" : null) +
            ", model=" + model +
            ", headers=" + headers +
            ", timeoutMillis=" + timeoutMillis +
            '}';
    }
}
