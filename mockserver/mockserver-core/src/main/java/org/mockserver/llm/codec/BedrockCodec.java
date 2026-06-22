package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingFormat;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    /**
     * Bedrock embeddings without a model hint default to the Amazon Titan shape.
     * Use the model-aware overload to select Cohere-on-Bedrock.
     */
    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        return encodeEmbedding(embedding, input, null);
    }

    /**
     * Encodes a Bedrock {@code InvokeModel} embedding response. Bedrock's
     * embedding wire shape is model-family specific:
     * <ul>
     *   <li><strong>Amazon Titan</strong> ({@code amazon.titan-embed-text-*}) —
     *       {@code {"embedding":[...],"inputTextTokenCount":N}}</li>
     *   <li><strong>Cohere</strong> ({@code cohere.embed-*}) —
     *       {@code {"embeddings":[[...]]}}</li>
     * </ul>
     * When {@code model} starts with {@code cohere} the Cohere shape is emitted,
     * otherwise the Titan shape (the Bedrock default). Both default to 1024
     * dimensions.
     */
    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input, String model) {
        double[] vector = EmbeddingVectors.build(embedding, input, 1024);
        ObjectNode root = OBJECT_MAPPER.createObjectNode();

        boolean cohere = model != null && model.toLowerCase().startsWith("cohere");
        if (cohere) {
            ArrayNode embeddings = root.putArray("embeddings");
            ArrayNode first = embeddings.addArray();
            for (double v : vector) {
                first.add(v);
            }
        } else {
            ArrayNode embeddingArray = root.putArray("embedding");
            for (double v : vector) {
                embeddingArray.add(v);
            }
            root.put("inputTextTokenCount", EmbeddingVectors.approximateTokens(input));
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Bedrock embedding response", e);
        }
    }
}
