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

    default ParsedConversation decode(HttpRequest request) {
        throw new UnsupportedOperationException("decode not implemented for provider " + provider());
    }
}
