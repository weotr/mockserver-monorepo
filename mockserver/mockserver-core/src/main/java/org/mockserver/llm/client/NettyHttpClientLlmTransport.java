package org.mockserver.llm.client;

import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.TimeUnit;

/**
 * {@link LlmTransport} backed by the server's {@link NettyHttpClient}. The
 * destination socket address is carried on the request (the per-provider
 * {@link LlmClient} sets the {@code Host} header and scheme), so the blocking
 * {@code sendRequest(request, timeout, unit)} overload routes it correctly.
 */
public class NettyHttpClientLlmTransport implements LlmTransport {

    private final NettyHttpClient httpClient;

    public NettyHttpClientLlmTransport(NettyHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public HttpResponse send(HttpRequest request, long timeoutMillis) {
        return httpClient.sendRequest(request, timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
