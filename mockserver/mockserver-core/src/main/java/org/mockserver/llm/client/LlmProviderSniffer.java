package org.mockserver.llm.client;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.net.URI;
import java.util.Optional;

/**
 * Maps a forwarded request's target host/URL to an LLM {@link Provider}, enabling
 * GenAI observability on the proxy/forward path. Pure function of its inputs (request
 * host/path, configuration) — no shared mutable state.
 * <p>
 * Detection order:
 * <ol>
 *   <li>Well-known provider hosts (exact or wildcard match)</li>
 *   <li>Configured {@code mockserver.llmBaseUrl} Ollama host match</li>
 *   <li>Fallback to configured {@code mockserver.llmProvider} — only when the
 *       request path looks like an LLM endpoint (contains any of
 *       {@code /chat/completions}, {@code /messages}, {@code /completions},
 *       {@code /responses}, {@code /embeddings}, {@code /v1/},
 *       {@code :generatecontent}, {@code /api/generate}, {@code /api/chat})</li>
 * </ol>
 * Returns {@link Optional#empty()} when the request is not LLM traffic.
 */
public final class LlmProviderSniffer {

    private LlmProviderSniffer() {
    }

    /**
     * Path fragments that indicate an LLM endpoint. Used to gate the configured-provider
     * fallback so non-LLM traffic to unknown hosts is never misclassified.
     * Checked case-insensitively.
     */
    private static final String[] LLM_PATH_FRAGMENTS = {
        "/chat/completions",
        "/messages",
        "/completions",
        "/responses",
        "/embeddings",
        "/v1/",
        ":generatecontent",
        "/api/generate",
        "/api/chat",
    };

    // Path-shape patterns for offline analysis detection (sniffByPath), mirroring
    // the dashboard's llmTraffic.ts. Matched against the original (case-sensitive)
    // path because the model name and method are case-sensitive.
    private static final java.util.regex.Pattern GEMINI_PATH_PATTERN = java.util.regex.Pattern.compile(
        "/v1beta/models/[^/]+:(generateContent|streamGenerateContent)"
            + "|/v1/models/gemini-[^/]+:(generateContent|streamGenerateContent)");
    private static final java.util.regex.Pattern OLLAMA_CHAT_PATTERN = java.util.regex.Pattern.compile(
        "(^|/)api/chat(/?$|\\?)");

    /**
     * Sniff the LLM provider from a forwarded request's target host.
     *
     * @param forwardedRequest the request that was forwarded to the upstream
     * @return the detected provider, or empty if this is not LLM traffic
     */
    public static Optional<Provider> sniff(HttpRequest forwardedRequest) {
        if (forwardedRequest == null) {
            return Optional.empty();
        }
        String path = forwardedRequest.getPath() != null
            ? forwardedRequest.getPath().getValue() : null;
        return sniffByHostAndPath(extractHost(forwardedRequest), path);
    }

    /**
     * Detect the LLM provider for OFFLINE analysis of already-captured traffic
     * (e.g. the optimisation report). Recognises LLM traffic that {@link #sniff}
     * cannot — most importantly MOCKED traffic served by MockServer itself on
     * localhost, where there is no upstream provider host. Tries the host-based
     * {@link #sniff} first, then falls back to {@link #sniffByPath}, which mirrors
     * the dashboard's client-side path detection so the SAME traffic appears in
     * both the Sessions view and the optimisation report.
     *
     * <p>Intended only for read-only analysis. The live forward/span path must
     * keep using host-gated {@link #sniff} so forwarded non-LLM traffic is never
     * mis-classified.
     */
    public static Optional<Provider> detectForAnalysis(HttpRequest request) {
        Optional<Provider> byHost = sniff(request);
        return byHost.isPresent() ? byHost : sniffByPath(request);
    }

    /**
     * Path-shape based provider detection, mirroring the dashboard's
     * {@code llmTraffic.ts} ordering (Anthropic, Azure, Bedrock, OpenAI Responses,
     * OpenAI, Gemini, Ollama). Used to analyse captured traffic whose host is not a
     * known provider (e.g. mocked LLM responses on localhost).
     */
    public static Optional<Provider> sniffByPath(HttpRequest request) {
        if (request == null || request.getPath() == null || request.getPath().getValue() == null) {
            return Optional.empty();
        }
        String path = request.getPath().getValue();
        String lower = path.toLowerCase();
        if (lower.contains("/v1/messages")) {
            return Optional.of(Provider.ANTHROPIC);
        }
        if (lower.contains("/openai/deployments/") && lower.contains("/chat/completions")) {
            return Optional.of(Provider.AZURE_OPENAI);
        }
        if (lower.contains("/model/anthropic.") && lower.contains("/invoke")) {
            return Optional.of(Provider.BEDROCK);
        }
        if (lower.contains("/v1/responses")) {
            return Optional.of(Provider.OPENAI_RESPONSES);
        }
        if (lower.contains("/chat/completions")) {
            return Optional.of(Provider.OPENAI);
        }
        if (GEMINI_PATH_PATTERN.matcher(path).find()) {
            return Optional.of(Provider.GEMINI);
        }
        if (OLLAMA_CHAT_PATTERN.matcher(path).find()) {
            return Optional.of(Provider.OLLAMA);
        }
        return Optional.empty();
    }

    /**
     * Sniff the LLM provider from an explicit host string (for unit testing or
     * callers that already have the host extracted). Equivalent to
     * {@code sniffByHostAndPath(host, null)} — the configured-provider fallback
     * is only applied when the path looks like an LLM endpoint, so with a null
     * path the fallback is skipped.
     */
    public static Optional<Provider> sniffByHost(String host) {
        return sniffByHostAndPath(host, null);
    }

    /**
     * Sniff the LLM provider from an explicit host and path.
     *
     * @param host the target host (may be null)
     * @param path the request path (may be null) — used only for the
     *             configured-provider fallback gate
     */
    public static Optional<Provider> sniffByHostAndPath(String host, String path) {
        if (host == null || host.isEmpty()) {
            return fallbackToConfiguredProvider(path);
        }
        String lowerHost = host.toLowerCase();

        // Well-known provider hosts — no path gate required
        if (lowerHost.equals("api.openai.com")) {
            // Distinguish the OpenAI Responses API (/responses) from the
            // Chat Completions API so LlmClientRegistry uses the correct parser
            if (path != null && path.toLowerCase().contains("/responses")) {
                return Optional.of(Provider.OPENAI_RESPONSES);
            }
            return Optional.of(Provider.OPENAI);
        }
        if (lowerHost.endsWith(".openai.azure.com")) {
            return Optional.of(Provider.AZURE_OPENAI);
        }
        if (lowerHost.equals("api.anthropic.com")) {
            return Optional.of(Provider.ANTHROPIC);
        }
        if (lowerHost.equals("generativelanguage.googleapis.com")) {
            return Optional.of(Provider.GEMINI);
        }
        if (isBedrockHost(lowerHost)) {
            return Optional.of(Provider.BEDROCK);
        }

        // Configured Ollama host: match against mockserver.llmBaseUrl
        String configuredBaseUrl = ConfigurationProperties.llmBaseUrl();
        if (configuredBaseUrl != null && !configuredBaseUrl.isEmpty()) {
            String configuredHost = extractHostFromUrl(configuredBaseUrl);
            if (configuredHost != null && lowerHost.equals(configuredHost.toLowerCase())) {
                return Optional.of(Provider.OLLAMA);
            }
        }

        // Fallback: configured mockserver.llmProvider — path-gated
        return fallbackToConfiguredProvider(path);
    }

    /**
     * Check if the host matches the Bedrock pattern: the host starts with "bedrock"
     * or contains ".bedrock" and ends with ".amazonaws.com".
     * Examples: bedrock-runtime.us-east-1.amazonaws.com, bedrock.eu-west-1.amazonaws.com
     */
    private static boolean isBedrockHost(String lowerHost) {
        if (!lowerHost.endsWith(".amazonaws.com")) {
            return false;
        }
        return lowerHost.startsWith("bedrock") || lowerHost.contains(".bedrock");
    }

    /**
     * Fallback to the configured {@code mockserver.llmProvider}, but only when
     * the request path looks like an LLM endpoint. This prevents a forward to
     * e.g. {@code example.com/api/users} from being misclassified as LLM traffic
     * just because a provider is configured.
     */
    private static Optional<Provider> fallbackToConfiguredProvider(String path) {
        if (!looksLikeLlmPath(path)) {
            return Optional.empty();
        }
        String configured = ConfigurationProperties.llmProvider();
        if (configured != null && !configured.isEmpty()) {
            try {
                return Optional.of(Provider.valueOf(configured.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // unrecognised provider name — not LLM traffic
            }
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} when the path (case-insensitive) contains any of the
     * well-known LLM endpoint fragments.
     */
    static boolean looksLikeLlmPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String lowerPath = path.toLowerCase();
        for (String fragment : LLM_PATH_FRAGMENTS) {
            if (lowerPath.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the host from the forwarded request. Prefers the socket address
     * host (set by the forward/proxy path), falling back to the Host header,
     * then the request path if it looks like a URL.
     */
    private static String extractHost(HttpRequest request) {
        // Socket address is the most reliable source on the forward path
        if (request.getSocketAddress() != null
            && request.getSocketAddress().getHost() != null
            && !request.getSocketAddress().getHost().isEmpty()) {
            return stripPort(request.getSocketAddress().getHost());
        }
        // Host header
        String hostHeader = request.getFirstHeader("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            return stripPort(hostHeader);
        }
        return null;
    }

    private static String extractHostFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripPort(String hostMaybeWithPort) {
        // Handle IPv6 addresses in brackets
        if (hostMaybeWithPort.startsWith("[")) {
            int closeBracket = hostMaybeWithPort.indexOf(']');
            if (closeBracket >= 0) {
                return hostMaybeWithPort.substring(1, closeBracket);
            }
        }
        int colon = hostMaybeWithPort.lastIndexOf(':');
        if (colon > 0) {
            return hostMaybeWithPort.substring(0, colon);
        }
        return hostMaybeWithPort;
    }

    /**
     * Extract the model name from the forwarded response body. Providers typically
     * include a {@code "model"} field in their JSON response (OpenAI, Anthropic,
     * Gemini use this pattern). Returns null if extraction fails.
     */
    public static String extractModelFromResponse(org.mockserver.model.HttpResponse response) {
        if (response == null) {
            return null;
        }
        try {
            String body = response.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().readTree(body);
            if (root != null && root.has("model")) {
                String model = root.path("model").asText();
                if (model != null && !model.isEmpty()) {
                    return model;
                }
            }
        } catch (Exception ignored) {
            // fail-soft: not parseable as JSON or no model field
        }
        return null;
    }

    /**
     * Extract the model name from the forwarded request body. Providers typically
     * include a {@code "model"} field in their JSON request body. Returns null if
     * extraction fails.
     */
    public static String extractModelFromRequest(HttpRequest request) {
        if (request == null) {
            return null;
        }
        try {
            String body = request.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().readTree(body);
            if (root != null && root.has("model")) {
                String model = root.path("model").asText();
                if (model != null && !model.isEmpty()) {
                    return model;
                }
            }
        } catch (Exception ignored) {
            // fail-soft: not parseable as JSON or no model field
        }
        return null;
    }
}
