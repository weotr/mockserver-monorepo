package org.mockserver.llm;

import org.mockserver.model.Provider;

/**
 * Pure, deterministic helper that produces the <strong>provider-specific</strong>
 * overload / rate-limit / server-error response bodies (and the matching default
 * HTTP status) that real LLM providers return. Client SDKs parse these distinct
 * error shapes to decide whether (and how) to retry/back off, so emitting them
 * faithfully lets a mock exercise that retry/backoff logic realistically — a
 * generic body would be silently ignored by an SDK that only retries on, say, an
 * {@code overloaded_error} type.
 *
 * <p>This is the body/status counterpart to {@link LlmRateLimitHeaders} (which
 * owns the rate-limit <em>headers</em>): the handler picks the body+status here
 * and stamps the headers there. All methods are static and pure (no clocks, no
 * randomness, no shared state).
 *
 * <h3>Provider error-shape reference</h3>
 * <ul>
 *   <li><strong>ANTHROPIC</strong> — top-level {@code {"type":"error","error":{"type":...,"message":...}}}.
 *       Overload: HTTP 529 {@code overloaded_error}; rate-limit: HTTP 429 {@code rate_limit_error};
 *       server error: HTTP 500 {@code api_error}. (source: Anthropic "Errors" docs.)</li>
 *   <li><strong>OPENAI / OPENAI_RESPONSES / AZURE_OPENAI</strong> —
 *       {@code {"error":{"message":...,"type":...,"param":null,"code":...}}}.
 *       Rate-limit: HTTP 429 type {@code rate_limit_exceeded}; overload/server: HTTP 503
 *       type {@code server_error}. (source: OpenAI "Error codes" docs.)</li>
 *   <li><strong>GEMINI</strong> — Google API envelope {@code {"error":{"code":N,"message":...,"status":...}}}.
 *       Rate-limit: HTTP 429 status {@code RESOURCE_EXHAUSTED}; overload: HTTP 503 status {@code UNAVAILABLE}.</li>
 *   <li><strong>BEDROCK</strong> — AWS JSON error {@code {"__type":...,"message":...}}.
 *       Rate-limit: HTTP 429 {@code ThrottlingException}; overload: HTTP 503 {@code ServiceUnavailableException}.</li>
 *   <li><strong>OLLAMA</strong> — local engine; simple {@code {"error":...}}. No real rate-limit/overload
 *       concept, so all kinds map to HTTP 500 with a generic message.</li>
 * </ul>
 */
public final class LlmErrorBody {

    /**
     * The kind of provider error to synthesise. The kind selects both the default
     * HTTP status and the provider-specific body shape.
     */
    public enum Kind {
        /** Provider is over capacity (e.g. Anthropic 529 overloaded_error, OpenAI 503 server_error). */
        OVERLOAD,
        /** Per-account rate / quota limit hit (e.g. 429 rate_limit_exceeded). */
        RATE_LIMIT,
        /** Generic upstream server error (5xx). */
        SERVER_ERROR
    }

    /** Immutable (status, jsonBody) pair returned by {@link #bodyFor}. */
    public static final class ErrorShape {
        private final int statusCode;
        private final String jsonBody;

        ErrorShape(int statusCode, String jsonBody) {
            this.statusCode = statusCode;
            this.jsonBody = jsonBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getJsonBody() {
            return jsonBody;
        }
    }

    private LlmErrorBody() {
    }

    /**
     * Returns the provider-specific error shape for the given {@code provider} and
     * {@code kind}, or {@code null} when the provider is unknown/null so the caller
     * can fall back to its generic body. The returned status is the provider's
     * natural status for that kind; the caller may override it (e.g. an explicit
     * {@code errorStatus} or {@code quotaErrorStatus} on the chaos profile) while
     * keeping the provider-correct body.
     *
     * @param provider the active LLM provider (may be {@code null})
     * @param kind     the error kind (never {@code null})
     * @return the provider error shape, or {@code null} for an unknown provider
     */
    public static ErrorShape bodyFor(Provider provider, Kind kind) {
        if (provider == null) {
            return null;
        }
        switch (provider) {
            case ANTHROPIC:
            case BEDROCK:
                // Bedrock's Anthropic models are fronted by the Bedrock runtime, which
                // returns AWS-shaped errors; the dedicated Bedrock branch below handles it.
                if (provider == Provider.BEDROCK) {
                    return bedrock(kind);
                }
                return anthropic(kind);
            case OPENAI:
            case OPENAI_RESPONSES:
            case AZURE_OPENAI:
                return openAi(kind);
            case GEMINI:
                return gemini(kind);
            case OLLAMA:
                return ollama(kind);
            default:
                return null;
        }
    }

    private static ErrorShape anthropic(Kind kind) {
        switch (kind) {
            case OVERLOAD:
                return new ErrorShape(529,
                    "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
            case RATE_LIMIT:
                return new ErrorShape(429,
                    "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Number of requests has exceeded your rate limit. Please try again later.\"}}");
            case SERVER_ERROR:
            default:
                return new ErrorShape(500,
                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal server error.\"}}");
        }
    }

    private static ErrorShape openAi(Kind kind) {
        switch (kind) {
            case OVERLOAD:
                return new ErrorShape(503,
                    "{\"error\":{\"message\":\"The server is overloaded or not ready yet.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");
            case RATE_LIMIT:
                return new ErrorShape(429,
                    "{\"error\":{\"message\":\"Rate limit reached for requests. Please try again later.\",\"type\":\"rate_limit_exceeded\",\"param\":null,\"code\":\"rate_limit_exceeded\"}}");
            case SERVER_ERROR:
            default:
                return new ErrorShape(500,
                    "{\"error\":{\"message\":\"The server had an error while processing your request.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");
        }
    }

    private static ErrorShape gemini(Kind kind) {
        switch (kind) {
            case OVERLOAD:
                return new ErrorShape(503,
                    "{\"error\":{\"code\":503,\"message\":\"The model is overloaded. Please try again later.\",\"status\":\"UNAVAILABLE\"}}");
            case RATE_LIMIT:
                return new ErrorShape(429,
                    "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
            case SERVER_ERROR:
            default:
                return new ErrorShape(500,
                    "{\"error\":{\"code\":500,\"message\":\"An internal error has occurred.\",\"status\":\"INTERNAL\"}}");
        }
    }

    private static ErrorShape bedrock(Kind kind) {
        switch (kind) {
            case OVERLOAD:
                return new ErrorShape(503,
                    "{\"__type\":\"ServiceUnavailableException\",\"message\":\"The service is currently unavailable. Please try again later.\"}");
            case RATE_LIMIT:
                return new ErrorShape(429,
                    "{\"__type\":\"ThrottlingException\",\"message\":\"Too many requests, please wait before trying again.\"}");
            case SERVER_ERROR:
            default:
                return new ErrorShape(500,
                    "{\"__type\":\"InternalServerException\",\"message\":\"An internal server error occurred.\"}");
        }
    }

    private static ErrorShape ollama(Kind kind) {
        // Ollama is a local inference engine with no real rate-limit/overload concept;
        // it surfaces failures as a plain {"error": ...} with a 500.
        switch (kind) {
            case OVERLOAD:
                return new ErrorShape(500, "{\"error\":\"server busy, please try again\"}");
            case RATE_LIMIT:
                return new ErrorShape(429, "{\"error\":\"too many requests\"}");
            case SERVER_ERROR:
            default:
                return new ErrorShape(500, "{\"error\":\"internal server error\"}");
        }
    }
}
