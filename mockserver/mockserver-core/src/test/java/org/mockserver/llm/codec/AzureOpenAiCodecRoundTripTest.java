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
import static org.mockserver.llm.codec.CodecTestUtil.escapeForJson;
import static org.mockserver.model.ToolUse.toolUse;

/**
 * Round-trip and edge-case tests for AzureOpenAiCodec.
 * Verifies that encoding a Completion produces wire-format JSON that,
 * when re-submitted as a request body, decodes back to semantically
 * equivalent content. Also covers edge branches not exercised by the
 * basic test class.
 */
public class AzureOpenAiCodecRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AzureOpenAiCodec codec = new AzureOpenAiCodec();

    // --- Round-trip: encode then decode preserves semantics ---

    @Test
    public void roundTripTextCompletion() throws Exception {
        Completion input = completion()
            .withText("The weather in Paris is sunny and 22C.")
            .withUsage(Usage.usage().withInputTokens(15).withOutputTokens(9));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Extract assistant message from encoded response and build a request
        // that includes the original user message + assistant reply (simulating
        // a multi-turn conversation re-submitted to the API)
        String assistantContent = root.get("choices").get(0).get("message").get("content").asText();

        String requestBody = "{\"messages\":[" +
            "{\"role\":\"user\",\"content\":\"What is the weather?\"}," +
            "{\"role\":\"assistant\",\"content\":\"" + escapeForJson(assistantContent) + "\"}" +
            "]}";
        HttpRequest nextRequest = request().withBody(requestBody);

        ParsedConversation decoded = codec.decode(nextRequest);

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(1).getTextContent(), is("The weather in Paris is sunny and 22C."));
    }

    @Test
    public void roundTripToolCallCompletion() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\",\"units\":\"metric\"}"))
            .withToolCall(toolUse("get_time").withArguments("{\"timezone\":\"UTC\"}"));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Extract tool_calls from the encoded response and rebuild as a request
        JsonNode toolCalls = root.get("choices").get(0).get("message").get("tool_calls");
        assertThat(toolCalls.size(), is(2));

        // Verify the tool call names and arguments survived encoding
        assertThat(toolCalls.get(0).get("function").get("name").asText(), is("get_weather"));
        assertThat(toolCalls.get(1).get("function").get("name").asText(), is("get_time"));

        // Decode the full assistant message including tool_calls
        String requestBody = "{\"messages\":[" +
            "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":" + toolCalls.toString() + "}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(1));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(0).getToolCalls(), hasSize(2));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(1).getName(), is("get_time"));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleEmptyTextCompletion() throws Exception {
        Completion input = completion().withText("");
        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // Empty text still produces a valid response
        assertThat(root.get("choices").get(0).get("message").has("content"), is(true));
    }

    @Test
    public void shouldHandleSpecialCharactersInText() throws Exception {
        String specialText = "Line1\nLine2\tTabbed \"quoted\" \\backslash and emoji: ❤";
        Completion input = completion().withText(specialText);

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        String encoded = root.get("choices").get(0).get("message").get("content").asText();
        assertThat(encoded, is(specialText));
    }

    @Test
    public void shouldHandleNullModelGracefully() throws Exception {
        Completion input = completion().withText("test");
        HttpResponse response = codec.encode(input, null);
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // Should not throw and should have some model value
        assertThat(root.has("model"), is(true));
    }

    @Test
    public void shouldHandleToolCallWithNullArguments() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("no_args"));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode toolCalls = root.get("choices").get(0).get("message").get("tool_calls");
        assertThat(toolCalls.size(), is(1));
        assertThat(toolCalls.get(0).get("function").get("name").asText(), is("no_args"));
    }

    @Test
    public void shouldHandleToolCallWithComplexNestedArguments() throws Exception {
        String complexArgs = "{\"filters\":{\"date\":{\"from\":\"2024-01-01\",\"to\":\"2024-12-31\"},\"tags\":[\"important\",\"urgent\"]},\"limit\":10}";
        Completion input = completion()
            .withToolCall(toolUse("search").withArguments(complexArgs));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        String encodedArgs = root.get("choices").get(0).get("message").get("tool_calls")
            .get(0).get("function").get("arguments").asText();
        // Arguments should be preserved as a JSON string
        JsonNode parsedArgs = OBJECT_MAPPER.readTree(encodedArgs);
        assertThat(parsedArgs.get("filters").get("tags").size(), is(2));
    }

    @Test
    public void shouldDecodeEmptyMessagesArray() {
        ParsedConversation parsed = codec.decode(request().withBody("{\"messages\":[]}"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldDecodeMultiTurnConversation() {
        String body = "{\"messages\":[" +
            "{\"role\":\"system\",\"content\":\"You are helpful.\"}," +
            "{\"role\":\"user\",\"content\":\"Hi\"}," +
            "{\"role\":\"assistant\",\"content\":\"Hello!\"}," +
            "{\"role\":\"user\",\"content\":\"Bye\"}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(4));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.SYSTEM));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(3).getRole(), is(ParsedMessage.Role.USER));
    }

    @Test
    public void shouldDefaultStreamingFormatToSSE() {
        assertThat(codec.streamingFormat(), is(StreamingFormat.SSE));
    }

    @Test
    public void streamingRoundTripPreservesTextContent() throws Exception {
        Completion input = completion()
            .withText("Hello world from Azure!")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(input, "gpt-4o", null);

        // Concatenate text deltas from streaming chunks
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            String data = event.getData();
            if ("[DONE]".equals(data)) continue;
            JsonNode chunk = OBJECT_MAPPER.readTree(data);
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            if (delta.has("content") && !delta.get("content").isNull()) {
                concatenated.append(delta.get("content").asText());
            }
        }
        assertThat(concatenated.toString(), is("Hello world from Azure!"));
    }

    @Test
    public void shouldEncodeEmbeddingWithHighDimensions() throws Exception {
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(1536)
            .withDeterministicFromInput(true)
            .withSeed(123L);

        HttpResponse response = codec.encodeEmbedding(embedding, "test input text");

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("data").get(0).get("embedding").size(), is(1536));
    }
}
