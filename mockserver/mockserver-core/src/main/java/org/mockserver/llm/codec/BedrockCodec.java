package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.model.*;

import java.util.List;

import static org.mockserver.model.HttpResponse.response;

/**
 * Codec for AWS Bedrock (Anthropic-on-Bedrock) invokeModel API (version bedrock-2023-05-31).
 * <p>
 * This codec targets the <strong>plain Anthropic body</strong> wire format used by
 * Bedrock's {@code invokeModel} endpoint for Anthropic Claude models. The request and
 * response shapes are essentially identical to native Anthropic Messages API — the
 * key difference is the model identifier format and URL path.
 * <p>
 * <strong>Limitation:</strong> Bedrock's {@code InvokeModelWithResponseStream} wraps
 * each SSE chunk in a binary {@code {"chunk":{"bytes":"..."}}} envelope. This codec
 * does <em>not</em> implement that binary chunk-wrapping framing, emitting instead
 * the plain Anthropic SSE event stream. This is sufficient for most testing scenarios
 * where the Bedrock SDK is configured to auto-decode the envelope, or where tests
 * mock at the HTTP layer after SDK decoding.
 */
public class BedrockCodec implements ProviderCodec {

    private final AnthropicCodec delegate = new AnthropicCodec();

    @Override
    public Provider provider() {
        return Provider.BEDROCK;
    }

    @Override
    public String apiVersion() {
        return "bedrock-2023-05-31";
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
        throw new UnsupportedOperationException("Bedrock embeddings use a model-specific shape not yet supported");
    }
}
