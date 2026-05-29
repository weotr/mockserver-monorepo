package org.mockserver.llm.client;

import org.mockserver.llm.ParsedConversation;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

/**
 * Per-provider knowledge for calling a real LLM as a client. Mirrors
 * {@link org.mockserver.llm.ProviderCodec} (which goes the other way —
 * decoding inbound requests / encoding mock responses): an {@code LlmClient}
 * <em>builds</em> an outbound request and <em>parses</em> the inbound response
 * into a {@link Completion}.
 * <p>
 * Deliberately split from transport: implementations are pure functions of
 * their inputs (no network, no shared state), so they unit-test offline. The
 * actual HTTP call, timeouts, fail-closed handling, and caching live in
 * {@link LlmCompletionService}. Adding a provider = implement this interface
 * and register it in {@link LlmClientRegistry} — the same one-line story as
 * codecs.
 */
public interface LlmClient {

    /**
     * The provider this client handles — the registry key.
     */
    Provider provider();

    /**
     * Build the outbound completion request (URL path, auth headers, request
     * body) for the given backend and prompt. The returned request carries the
     * socket address resolved from the backend's base URL (or the provider
     * default). Implementations pin {@code temperature=0} and a seed where the
     * provider supports it, for reproducibility.
     */
    HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt);

    /**
     * Parse a successful provider response body into a {@link Completion}.
     * Implementations should tolerate missing optional fields and never throw
     * for a well-formed-but-sparse response; malformed bodies may throw and are
     * handled (fail-closed) by {@link LlmCompletionService}.
     */
    Completion parseCompletionResponse(HttpResponse response);
}
