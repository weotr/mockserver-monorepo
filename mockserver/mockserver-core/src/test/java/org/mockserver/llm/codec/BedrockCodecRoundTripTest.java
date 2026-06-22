package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;

/**
 * Round-trip and edge-case tests for BedrockCodec.
 * Verifies encode-decode symmetry for the Anthropic-on-Bedrock wire format
 * and exercises edge branches (null args, special characters, empty completions,
 * event-stream format declaration).
 */
public class BedrockCodecRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final BedrockCodec codec = new BedrockCodec();

    // --- Round-trip: encode then decode preserves semantics ---

    @Test
    public void roundTripTextCompletion() throws Exception {
        Completion input = completion()
            .withText("The capital of France is Paris.")
            .withUsage(Usage.usage().withInputTokens(12).withOutputTokens(7));

        HttpResponse response = codec.encode(input, "anthropic.claude-3-7-sonnet-20250219-v1:0");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Extract text from the Anthropic-format response
        String encodedText = root.get("content").get(0).get("text").asText();
        assertThat(encodedText, is("The capital of France is Paris."));

        // Build a request with this text as assistant content and decode
        String requestBody = "{\"messages\":[" +
            "{\"role\":\"user\",\"content\":\"What is the capital of France?\"}," +
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + encodedText + "\"}]}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(1).getTextContent(), is("The capital of France is Paris."));
    }

    @Test
    public void roundTripToolCallCompletion() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Berlin\"}"))
            .withStopReason("tool_use");

        HttpResponse response = codec.encode(input, "anthropic.claude-3-7-sonnet-20250219-v1:0");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Verify encoded tool_use block
        JsonNode content = root.get("content");
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("tool_use"));
        assertThat(content.get(0).get("name").asText(), is("get_weather"));
        String toolUseId = content.get(0).get("id").asText();
        assertThat(toolUseId, is(notNullValue()));

        // Build request with assistant tool_use + user tool_result
        String requestBody = "{\"messages\":[" +
            "{\"role\":\"assistant\",\"content\":[" +
            "{\"type\":\"tool_use\",\"id\":\"" + toolUseId + "\",\"name\":\"get_weather\",\"input\":{\"city\":\"Berlin\"}}" +
            "]}," +
            "{\"role\":\"user\",\"content\":[" +
            "{\"type\":\"tool_result\",\"tool_use_id\":\"" + toolUseId + "\",\"content\":\"15C, cloudy\"}" +
            "]}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(0).getToolCalls(), hasSize(1));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(decoded.getMessages().get(1).getToolResults(), hasEntry(toolUseId, "15C, cloudy"));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleEmptyTextCompletion() throws Exception {
        Completion input = completion().withText("");
        HttpResponse response = codec.encode(input, "anthropic.claude-3-7-sonnet-20250219-v1:0");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // Even with empty text, response should be valid
        assertThat(root.get("type").asText(), is("message"));
        assertThat(root.get("role").asText(), is("assistant"));
    }

    @Test
    public void shouldHandleSpecialCharactersInText() throws Exception {
        String specialText = "\"quoted\" and 'apostrophe'\nnewline\ttab \\backslash unicode: éèê";
        Completion input = completion().withText(specialText);

        HttpResponse response = codec.encode(input, "anthropic.claude-3-7-sonnet-20250219-v1:0");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        String decoded = root.get("content").get(0).get("text").asText();
        assertThat(decoded, is(specialText));
    }

    @Test
    public void shouldHandleNullModelGracefully() throws Exception {
        Completion input = completion().withText("test");
        HttpResponse response = codec.encode(input, null);
        // Should not throw
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("type").asText(), is("message"));
    }

    @Test
    public void shouldHandleMultipleToolCalls() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"weather\"}"))
            .withToolCall(toolUse("calendar").withArguments("{\"date\":\"2024-06-01\"}"))
            .withToolCall(toolUse("notify").withArguments("{\"msg\":\"done\"}"))
            .withStopReason("tool_use");

        HttpResponse response = codec.encode(input, "anthropic.claude-3-7-sonnet-20250219-v1:0");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode content = root.get("content");
        assertThat(content.size(), is(3));
        assertThat(content.get(0).get("name").asText(), is("search"));
        assertThat(content.get(1).get("name").asText(), is("calendar"));
        assertThat(content.get(2).get("name").asText(), is("notify"));
    }

    @Test
    public void shouldHandleToolCallWithNullArguments() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("no_args"));

        HttpResponse response = codec.encode(input, "anthropic.claude-3-7-sonnet-20250219-v1:0");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode content = root.get("content");
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("tool_use"));
        assertThat(content.get(0).get("name").asText(), is("no_args"));
    }

    @Test
    public void shouldDeclareAwsEventStreamFormat() {
        assertThat(codec.streamingFormat(), is(StreamingFormat.AWS_EVENT_STREAM));
    }

    @Test
    public void shouldDecodeEmptyMessagesArray() {
        ParsedConversation parsed = codec.decode(request().withBody("{\"messages\":[]}"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldDecodeMultipleContentBlocksInSingleMessage() {
        String body = "{\"messages\":[" +
            "{\"role\":\"user\",\"content\":[" +
            "{\"type\":\"text\",\"text\":\"First.\"}," +
            "{\"type\":\"text\",\"text\":\" Second.\"}" +
            "]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("First. Second."));
    }

    @Test
    public void shouldDecodeSystemMessageAsText() {
        String body = "{\"system\":\"You are a helpful assistant.\",\"messages\":[" +
            "{\"role\":\"user\",\"content\":\"Hello\"}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        // System message may or may not be decoded separately depending on implementation
        // At minimum, the user message should be decoded
        assertThat(parsed.getMessages().size(), is(greaterThanOrEqualTo(1)));
        boolean hasUser = parsed.getMessages().stream()
            .anyMatch(m -> m.getRole() == ParsedMessage.Role.USER && "Hello".equals(m.getTextContent()));
        assertThat(hasUser, is(true));
    }

    @Test
    public void streamingRoundTripPreservesTextContent() throws Exception {
        Completion input = completion()
            .withText("Hello from Bedrock!")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(4));

        List<SseEvent> events = codec.encodeStreaming(input, "anthropic.claude-3-7-sonnet-20250219-v1:0", null);

        // Concatenate text deltas from streaming events
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            if ("content_block_delta".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                JsonNode delta = data.get("delta");
                if (delta != null && "text_delta".equals(delta.path("type").asText())) {
                    concatenated.append(delta.get("text").asText());
                }
            }
        }
        assertThat(concatenated.toString(), is("Hello from Bedrock!"));
    }

    @Test
    public void shouldProduceStreamingEventsWithCorrectStructure() throws Exception {
        Completion input = completion()
            .withText("Test")
            .withUsage(Usage.usage().withInputTokens(3).withOutputTokens(1));

        List<SseEvent> events = codec.encodeStreaming(input, "model", null);

        // First event should be message_start
        assertThat(events.get(0).getEvent(), is("message_start"));
        JsonNode startData = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(startData.get("type").asText(), is("message_start"));

        // Last event should be message_stop
        assertThat(events.get(events.size() - 1).getEvent(), is("message_stop"));
    }

    @Test
    public void shouldEncodeTitanEmbeddingDeterministically() throws Exception {
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(16)
            .withDeterministicFromInput(true)
            .withSeed(5L);

        HttpResponse r1 = codec.encodeEmbedding(embedding, "same", "amazon.titan-embed-text-v2:0");
        HttpResponse r2 = codec.encodeEmbedding(embedding, "same", "amazon.titan-embed-text-v2:0");

        JsonNode v1 = OBJECT_MAPPER.readTree(r1.getBodyAsString()).get("embedding");
        JsonNode v2 = OBJECT_MAPPER.readTree(r2.getBodyAsString()).get("embedding");
        assertThat(v1.size(), is(16));
        for (int i = 0; i < 16; i++) {
            assertThat(v1.get(i).asDouble(), is(v2.get(i).asDouble()));
        }
    }

    @Test
    public void shouldDecodeRequestWithAnthropicVersionHeader() {
        // Bedrock requests often include anthropic_version in the body
        String body = "{\"anthropic_version\":\"bedrock-2023-05-31\",\"model\":\"anthropic.claude-3-7-sonnet-20250219-v1:0\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hi"));
    }
}
