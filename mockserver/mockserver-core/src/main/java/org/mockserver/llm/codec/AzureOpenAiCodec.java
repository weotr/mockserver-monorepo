package org.mockserver.llm.codec;

import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.model.*;

import java.util.List;

/**
 * Codec for Azure OpenAI Service (API version 2024-10-21).
 * <p>
 * Azure OpenAI is wire-compatible with OpenAI Chat Completions — the response and
 * streaming shapes are identical. The differences are in URL paths
 * ({@code /openai/deployments/{deployment}/chat/completions?api-version=...})
 * and authentication headers ({@code api-key} instead of {@code Authorization: Bearer}).
 * <p>
 * This codec delegates all encoding and decoding to {@link OpenAiChatCompletionsCodec},
 * overriding only {@link #provider()} and {@link #apiVersion()}.
 */
public class AzureOpenAiCodec implements ProviderCodec {

    private final OpenAiChatCompletionsCodec delegate = new OpenAiChatCompletionsCodec();

    @Override
    public Provider provider() {
        return Provider.AZURE_OPENAI;
    }

    @Override
    public String apiVersion() {
        return "2024-10-21";
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        return delegate.encode(completion, model);
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        return delegate.encodeStreaming(completion, model, physics);
    }

    @Override
    public ParsedConversation decode(HttpRequest request) {
        return delegate.decode(request);
    }

    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        return delegate.encodeEmbedding(embedding, input);
    }
}
