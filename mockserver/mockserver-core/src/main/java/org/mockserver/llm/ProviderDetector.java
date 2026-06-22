package org.mockserver.llm;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic, read-only heuristic that infers the {@link Provider} from an
 * {@link HttpRequest}'s path (and, as a fallback, its Host header). This
 * mirrors the UI-side provider detection in {@code llmTraffic.ts} and is used
 * by the MCP analysis tools to support an {@code "AUTO"} provider value for
 * proxied traffic where the caller may not know which provider was recorded.
 * <p>
 * Detection order matches the UI: Anthropic, Azure OpenAI, Bedrock, OpenAI
 * Responses, OpenAI Chat Completions, Gemini, Ollama. The first match wins.
 */
public final class ProviderDetector {

    // Anthropic: /v1/messages
    private static final Pattern ANTHROPIC_PATH = Pattern.compile("/v1/messages");

    // Azure OpenAI: /openai/deployments/.../chat/completions
    private static final Pattern AZURE_OPENAI_PATH = Pattern.compile("/openai/deployments/.*/chat/completions");

    // Bedrock: /model/anthropic.*/invoke
    private static final Pattern BEDROCK_PATH = Pattern.compile("/model/anthropic\\..*/invoke");

    // OpenAI Responses: /v1/responses
    private static final Pattern OPENAI_RESPONSES_PATH = Pattern.compile("/v1/responses");

    // OpenAI Chat Completions: /chat/completions (but not Azure pattern)
    private static final Pattern OPENAI_PATH = Pattern.compile("/chat/completions");

    // Gemini: /v1beta/models/...:(generateContent|streamGenerateContent)
    //      or /v1/models/gemini-...:(generateContent|streamGenerateContent)
    private static final Pattern GEMINI_PATH = Pattern.compile(
        "/v1beta/models/[^/]+:(generateContent|streamGenerateContent)"
            + "|/v1/models/gemini-[^/]+:(generateContent|streamGenerateContent)");

    // Ollama: /api/chat (as a complete path segment)
    private static final Pattern OLLAMA_PATH = Pattern.compile("(^|/)api/chat(/|$|\\?)");

    private ProviderDetector() {
    }

    /**
     * Attempt to detect the LLM provider from an HTTP request's path.
     * Returns empty if no provider can be inferred.
     */
    public static Optional<Provider> detect(HttpRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        if (path != null) {
            Optional<Provider> fromPath = detectFromPath(path);
            if (fromPath.isPresent()) {
                return fromPath;
            }
        }
        return Optional.empty();
    }

    /**
     * Detect the provider from a request path string alone.
     */
    public static Optional<Provider> detectFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }

        // Order matches UI detection (most specific first)
        if (ANTHROPIC_PATH.matcher(path).find()) {
            return Optional.of(Provider.ANTHROPIC);
        }
        if (AZURE_OPENAI_PATH.matcher(path).find()) {
            return Optional.of(Provider.AZURE_OPENAI);
        }
        if (BEDROCK_PATH.matcher(path).find()) {
            return Optional.of(Provider.BEDROCK);
        }
        if (OPENAI_RESPONSES_PATH.matcher(path).find()) {
            return Optional.of(Provider.OPENAI_RESPONSES);
        }
        if (OPENAI_PATH.matcher(path).find()) {
            return Optional.of(Provider.OPENAI);
        }
        if (GEMINI_PATH.matcher(path).find()) {
            return Optional.of(Provider.GEMINI);
        }
        if (OLLAMA_PATH.matcher(path).find()) {
            return Optional.of(Provider.OLLAMA);
        }

        return Optional.empty();
    }

    /**
     * Auto-detect the provider from a list of recorded requests by scanning
     * their paths. Returns the first detected provider, or empty if none can
     * be inferred.
     */
    public static Optional<Provider> detectFromRequests(java.util.List<HttpRequest> requests) {
        if (requests == null) {
            return Optional.empty();
        }
        for (HttpRequest request : requests) {
            Optional<Provider> detected = detect(request);
            if (detected.isPresent()) {
                return detected;
            }
        }
        return Optional.empty();
    }
}
