package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.model.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;

/**
 * Tests for the Bedrock codec (Anthropic-on-Bedrock wire format).
 * Since Bedrock delegates to AnthropicCodec, these tests verify delegation
 * is correct and the provider/version metadata is distinct. Streaming tests
 * verify that chunks can be decoded via the AWS event-stream binary format.
 */
public class BedrockCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final BedrockCodec codec = new BedrockCodec();

    // --- Provider & Version ---

    @Test
    public void shouldReturnBedrockProviderAndVersion() {
        assertThat(codec.provider(), is(Provider.BEDROCK));
        assertThat(codec.apiVersion(), is("bedrock-2023-05-31"));
    }

    // --- Encode Non-Streaming (Anthropic format) ---

    @Test
    public void shouldEncodeTextCompletionInAnthropicFormat() throws Exception {
        Completion completion = completion()
            .withText("Hello from Bedrock.")
            .withUsage(Usage.usage().withInputTokens(20).withOutputTokens(4));

        HttpResponse response = codec.encode(completion, "anthropic.claude-3-7-sonnet-20250219-v1:0");

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("id").asText(), startsWith("msg_"));
        assertThat(root.get("type").asText(), is("message"));
        assertThat(root.get("role").asText(), is("assistant"));
        assertThat(root.get("model").asText(), is("anthropic.claude-3-7-sonnet-20250219-v1:0"));

        JsonNode content = root.get("content");
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("text"));
        assertThat(content.get(0).get("text").asText(), is("Hello from Bedrock."));

        assertThat(root.get("stop_reason").asText(), is("end_turn"));
        JsonNode usage = root.get("usage");
        assertThat(usage.get("input_tokens").asInt(), is(20));
        assertThat(usage.get("output_tokens").asInt(), is(4));
    }

    @Test
    public void shouldEncodeToolCallInAnthropicFormat() throws Exception {
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"))
            .withStopReason("tool_use");

        HttpResponse response = codec.encode(completion, "anthropic.claude-3-7-sonnet-20250219-v1:0");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode content = root.get("content");
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("tool_use"));
        assertThat(content.get(0).get("name").asText(), is("search"));
        assertThat(root.get("stop_reason").asText(), is("tool_use"));
    }

    @Test
    public void shouldSetContentTypeHeader() throws Exception {
        Completion completion = completion().withText("test");
        HttpResponse response = codec.encode(completion, "model");
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
    }

    @Test
    public void shouldDefaultUsageToZeros() throws Exception {
        Completion completion = completion().withText("test");
        HttpResponse response = codec.encode(completion, "model");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("usage").get("input_tokens").asInt(), is(0));
        assertThat(root.get("usage").get("output_tokens").asInt(), is(0));
    }

    // --- Encode Streaming (Anthropic SSE events) ---

    @Test
    public void shouldProduceAnthropicStreamingEvents() throws Exception {
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(completion, "anthropic.claude-3-7-sonnet-20250219-v1:0", null);

        assertThat(events.size(), is(greaterThanOrEqualTo(6)));
        assertThat(events.get(0).getEvent(), is("message_start"));
        assertThat(events.get(events.size() - 1).getEvent(), is("message_stop"));
    }

    @Test
    public void shouldProduceCorrectStopReasonInStreamingDelta() throws Exception {
        Completion completion = completion()
            .withText("Hello")
            .withStopReason("end_turn");

        List<SseEvent> events = codec.encodeStreaming(completion, "model", null);

        SseEvent messageDelta = null;
        for (SseEvent event : events) {
            if ("message_delta".equals(event.getEvent())) {
                messageDelta = event;
            }
        }
        assertThat(messageDelta, is(notNullValue()));
        JsonNode data = OBJECT_MAPPER.readTree(messageDelta.getData());
        assertThat(data.get("delta").get("stop_reason").asText(), is("end_turn"));
    }

    // --- Decode (Anthropic messages format) ---

    @Test
    public void shouldDecodeAnthropicFormatMessages() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"anthropic.claude-3-7-sonnet-20250219-v1:0\",\n" +
                "  \"anthropic_version\": \"bedrock-2023-05-31\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"What is 2+2?\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("What is 2+2?"));
    }

    @Test
    public void shouldDecodeToolUseAndToolResult() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"anthropic.claude-3-7-sonnet-20250219-v1:0\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Search\"},\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"tool_use\", \"id\": \"toolu_abc\", \"name\": \"search\", \"input\": {\"q\": \"test\"}}\n" +
                "    ]},\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_abc\", \"content\": \"result data\"}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(3));
        assertThat(parsed.getMessages().get(1).getToolCalls(), hasSize(1));
        assertThat(parsed.getMessages().get(1).getToolCalls().get(0).getName(), is("search"));
        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(parsed.getMessages().get(2).getToolResults(), hasEntry("toolu_abc", "result data"));
    }

    @Test
    public void shouldReturnEmptyForMalformedJson() {
        ParsedConversation parsed = codec.decode(request().withBody("not json"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForNullRequest() {
        ParsedConversation parsed = codec.decode(null);
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForMissingMessages() {
        ParsedConversation parsed = codec.decode(request().withBody("{\"model\": \"m\"}"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowForEmbeddings() {
        codec.encodeEmbedding(EmbeddingResponse.embedding(), "test input");
    }

    // --- Streaming Format ---

    @Test
    public void shouldDeclareAwsEventStreamStreamingFormat() {
        assertThat(codec.streamingFormat(), is(StreamingFormat.AWS_EVENT_STREAM));
    }

    // --- Event-Stream Streaming Integration ---

    @Test
    public void shouldProduceStreamingChunksThatDecodeViaEventStream() throws Exception {
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(completion,
            "anthropic.claude-3-7-sonnet-20250219-v1:0", null);

        assertThat(events.size(), is(greaterThanOrEqualTo(6)));
        assertThat(events.get(0).getEvent(), is("message_start"));
        assertThat(events.get(events.size() - 1).getEvent(), is("message_stop"));

        // Simulate the event-stream binary encoding that HttpSseResponseActionHandler performs.
        // Each event's data is wrapped via BedrockEventStreamEncoder.encodeChunk.
        for (SseEvent event : events) {
            String chunkJson = event.getData();
            assertThat("event data must not be null", chunkJson, is(notNullValue()));

            byte[] encoded = BedrockEventStreamEncoder.encodeChunk(chunkJson);
            List<BedrockEventStreamEncoder.DecodedMessage> decoded =
                BedrockEventStreamEncoder.decode(encoded);

            assertThat(decoded, hasSize(1));

            // Verify headers
            assertThat(decoded.get(0).getHeaders().get(":event-type"), is("chunk"));
            assertThat(decoded.get(0).getHeaders().get(":content-type"), is("application/json"));
            assertThat(decoded.get(0).getHeaders().get(":message-type"), is("event"));

            // Verify base64 payload round-trips to the original chunk JSON
            JsonNode payloadNode = OBJECT_MAPPER.readTree(decoded.get(0).getPayloadAsString());
            String base64 = payloadNode.get("bytes").asText();
            String roundTripped = new String(
                Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            assertThat(roundTripped, is(chunkJson));
        }
    }

    @Test
    public void shouldProduceFullStreamThatDecodesIntoOrderedChunks() throws Exception {
        Completion completion = completion()
            .withText("Test stream")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(3));

        List<SseEvent> events = codec.encodeStreaming(completion,
            "anthropic.claude-3-7-sonnet-20250219-v1:0", null);

        // Build full event-stream byte array (simulating HttpSseResponseActionHandler)
        java.io.ByteArrayOutputStream fullStream = new java.io.ByteArrayOutputStream();
        for (SseEvent event : events) {
            fullStream.write(BedrockEventStreamEncoder.encodeChunk(event.getData()));
        }

        // Decode the full stream
        List<BedrockEventStreamEncoder.DecodedMessage> decoded =
            BedrockEventStreamEncoder.decode(fullStream.toByteArray());

        assertThat("decoded count must match event count",
            decoded.size(), is(events.size()));

        // First chunk should be message_start
        JsonNode firstPayload = OBJECT_MAPPER.readTree(
            new String(Base64.getDecoder().decode(
                OBJECT_MAPPER.readTree(decoded.get(0).getPayloadAsString())
                    .get("bytes").asText()), StandardCharsets.UTF_8));
        assertThat(firstPayload.get("type").asText(), is("message_start"));

        // Last chunk should be message_stop
        JsonNode lastPayload = OBJECT_MAPPER.readTree(
            new String(Base64.getDecoder().decode(
                OBJECT_MAPPER.readTree(decoded.get(decoded.size() - 1).getPayloadAsString())
                    .get("bytes").asText()), StandardCharsets.UTF_8));
        assertThat(lastPayload.get("type").asText(), is("message_stop"));
    }
}
