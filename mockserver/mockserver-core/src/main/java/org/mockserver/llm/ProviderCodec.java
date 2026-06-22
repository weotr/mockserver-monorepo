package org.mockserver.llm;

import org.mockserver.model.*;

import java.util.List;

public interface ProviderCodec {

    Provider provider();

    String apiVersion();

    default HttpResponse encode(Completion completion, String model) {
        throw new UnsupportedOperationException("encode not implemented for provider " + provider());
    }

    default List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        throw new UnsupportedOperationException("encodeStreaming not implemented for provider " + provider());
    }

    /**
     * The wire format this provider uses for streaming responses.
     * Defaults to {@link StreamingFormat#SSE}; override for providers
     * that use a different format (e.g. Ollama uses NDJSON).
     */
    default StreamingFormat streamingFormat() {
        return StreamingFormat.SSE;
    }

    default HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        throw new UnsupportedOperationException("encodeEmbedding not implemented for provider " + provider());
    }

    /**
     * Model-aware embedding encode. Most providers have a single embedding wire
     * shape and ignore the model, so the default delegates to
     * {@link #encodeEmbedding(EmbeddingResponse, String)}. Providers whose
     * embedding shape varies by model family (e.g. Bedrock Titan vs Cohere)
     * override this to branch on {@code model}.
     */
    default HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input, String model) {
        return encodeEmbedding(embedding, input);
    }

    /**
     * Encode a rerank response for providers that expose a rerank endpoint
     * (e.g. Cohere {@code /v1/rerank}, Voyage {@code /v1/rerank}). Each result is
     * a {@code {"index":N,"relevance_score":F}} entry, one per candidate document,
     * sorted by descending relevance. The surrounding envelope is provider-specific
     * (Cohere uses a top-level {@code results} array; Voyage uses an OpenAI-style
     * {@code data} list with an {@code object}/{@code usage} wrapper).
     */
    default HttpResponse encodeRerank(RerankResponse rerank, java.util.List<String> documents) {
        throw new UnsupportedOperationException("encodeRerank not implemented for provider " + provider());
    }

    default ParsedConversation decode(HttpRequest request) {
        throw new UnsupportedOperationException("decode not implemented for provider " + provider());
    }
}
