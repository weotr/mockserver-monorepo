package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.List;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Codec for AWS Bedrock (Anthropic-on-Bedrock) invokeModel API (version bedrock-2023-05-31).
 * <p>
 * This codec targets the <strong>plain Anthropic body</strong> wire format used by
 * Bedrock's {@code invokeModel} endpoint for Anthropic Claude models. The request and
 * response shapes are essentially identical to native Anthropic Messages API — the
 * key difference is the model identifier format and URL path.
 * <p>
 * <strong>Streaming:</strong> Bedrock's {@code InvokeModelWithResponseStream} uses the
 * AWS event-stream binary framing ({@code application/vnd.amazon.eventstream}). Each
 * streaming chunk is wrapped as a binary message with headers
 * ({@code :event-type=chunk}, {@code :content-type=application/json},
 * {@code :message-type=event}) and a payload of
 * {@code {"bytes":"<base64(chunkJson)>"}}. This codec declares
 * {@link StreamingFormat#AWS_EVENT_STREAM} and the downstream write handler
 * ({@link org.mockserver.mock.action.http.HttpSseResponseActionHandler}) encodes each
 * chunk into the binary event-stream format via {@link BedrockEventStreamEncoder}.
 * <p>
 * <strong>SigV4 signing:</strong> automatic AWS SigV4 request signing for calling
 * real Bedrock is <em>not yet implemented</em>. Callers should supply auth headers
 * via the {@code LlmBackend.headers()} escape hatch or a signing proxy. This remains
 * a follow-up.
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
    public StreamingFormat streamingFormat() {
        return StreamingFormat.AWS_EVENT_STREAM;
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        return delegate.encode(completion, model);
    }

    /**
     * Encode a streaming completion as event-stream chunks.
     * <p>
     * Delegates to {@link AnthropicCodec#encodeStreaming} to produce the Anthropic
     * SSE events, then transforms each event's {@code data} payload into a form
     * suitable for event-stream binary wrapping. The downstream write handler
     * performs the actual binary encoding via {@link BedrockEventStreamEncoder}.
     * <p>
     * Each event's data becomes one event-stream chunk whose payload is
     * {@code {"bytes":"<base64(data)>"}}.
     */
    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        // Get the Anthropic-format SSE events (with physics already applied)
        List<SseEvent> anthropicEvents = delegate.encodeStreaming(completion, model, physics);

        // Return events as-is — the data payloads are the raw Anthropic chunk JSON.
        // The HttpSseResponseActionHandler will wrap each chunk in event-stream
        // binary framing (including base64 encoding) when it detects
        // StreamingFormat.AWS_EVENT_STREAM.
        return anthropicEvents;
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
