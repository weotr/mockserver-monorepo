package org.mockserver.llm.client;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * Transport seam for outbound runtime-LLM calls. Abstracts the HTTP send so
 * {@link LlmCompletionService} can be unit-tested with a stub and wired to the
 * running server's {@link org.mockserver.httpclient.NettyHttpClient} in
 * production (see {@link NettyHttpClientLlmTransport}).
 */
public interface LlmTransport {

    /**
     * Send the request and return the response, or throw on transport failure.
     * Implementations must respect {@code timeoutMillis} (the
     * {@link LlmCompletionService} treats a timeout or exception fail-closed).
     */
    HttpResponse send(HttpRequest request, long timeoutMillis);
}
