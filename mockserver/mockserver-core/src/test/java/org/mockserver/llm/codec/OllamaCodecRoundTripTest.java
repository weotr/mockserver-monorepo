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
 * Round-trip and edge-case tests for OllamaCodec.
 * Verifies that encoding a Completion produces wire-format JSON that preserves
 * semantics when decoded, and exercises edge branches: special characters,
 * null arguments, empty text, multi-tool calls, NDJSON streaming format.
 */
public class OllamaCodecRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OllamaCodec codec = new OllamaCodec();

    // --- Round-trip: encode then verify content matches decode semantics ---

    @Test
    public void roundTripTextCompletion() throws Exception {
        Completion input = completion()
            .withText("Llamas are fascinating animals native to South America.")
            .withUsage(Usage.usage().withInputTokens(8).withOutputTokens(9));

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Verify encoded structure
        String encodedText = root.get("message").get("content").asText();
        assertThat(encodedText, is("Llamas are fascinating animals native to South America."));

        // Build an Ollama-format request with this text as assistant reply and decode
        String requestBody = "{\"model\":\"llama3.1\",\"messages\":[" +
            "{\"role\":\"user\",\"content\":\"Tell me about llamas\"}," +
            "{\"role\":\"assistant\",\"content\":\"" + escapeForJson(encodedText) + "\"}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(1).getTextContent(), is("Llamas are fascinating animals native to South America."));
    }

    @Test
    public void roundTripToolCallCompletion() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Tokyo\",\"units\":\"celsius\"}"));

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Verify tool call in encoded response
        JsonNode toolCalls = root.get("message").get("tool_calls");
        assertThat(toolCalls.size(), is(1));
        assertThat(toolCalls.get(0).get("function").get("name").asText(), is("get_weather"));
        // Ollama uses JSON object for arguments (not string)
        assertThat(toolCalls.get(0).get("function").get("arguments").get("city").asText(), is("Tokyo"));

        // Build request with assistant tool_calls and decode
        String requestBody = "{\"model\":\"llama3.1\",\"messages\":[" +
            "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[" +
            "{\"function\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Tokyo\",\"units\":\"celsius\"}}}" +
            "]}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(1));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(0).getToolCalls(), hasSize(1));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getArguments(), containsString("Tokyo"));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleEmptyTextWithToolCalls() throws Exception {
        Completion input = completion()
            .withText("")
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"));

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("message").get("content").asText(), is(""));
        assertThat(root.get("message").get("tool_calls").size(), is(1));
    }

    @Test
    public void shouldHandleSpecialCharactersInTextEncoding() throws Exception {
        String specialText = "Code: `x = \"hello\"`\nNewline and\ttab. Unicode: 日本語";
        Completion input = completion().withText(specialText);

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        String decoded = root.get("message").get("content").asText();
        assertThat(decoded, is(specialText));
    }

    @Test
    public void shouldHandleNullModelDefaultingToUnknown() throws Exception {
        Completion input = completion().withText("test");
        HttpResponse response = codec.encode(input, null);
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("model").asText(), is("unknown"));
    }

    @Test
    public void shouldHandleToolCallWithNullArguments() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("ping"));

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode tc = root.get("message").get("tool_calls").get(0);
        assertThat(tc.get("function").get("name").asText(), is("ping"));
        // null args should produce empty object
        assertThat(tc.get("function").get("arguments").isObject(), is(true));
    }

    @Test
    public void shouldHandleToolCallWithNonObjectArguments() throws Exception {
        // Non-JSON-object string arguments should be wrapped in {"value":"..."}
        Completion input = completion()
            .withToolCall(toolUse("fn").withArguments("plain text"));

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode args = root.get("message").get("tool_calls").get(0).get("function").get("arguments");
        assertThat(args.isObject(), is(true));
        assertThat(args.get("value").asText(), is("plain text"));
    }

    @Test
    public void shouldHandleToolCallWithInvalidJsonArguments() throws Exception {
        // Malformed JSON arguments should not crash, should be wrapped safely
        Completion input = completion()
            .withToolCall(toolUse("fn").withArguments("{broken json"));

        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // Response should still be valid JSON
        JsonNode args = root.get("message").get("tool_calls").get(0).get("function").get("arguments");
        assertThat(args.isObject(), is(true));
    }

    @Test
    public void shouldDecodeMessagesWithTextAsStringArguments() {
        // Ollama supports both string and object form for tool call arguments in decode
        String body = "{\"messages\":[" +
            "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[" +
            "{\"function\":{\"name\":\"search\",\"arguments\":\"plain_string\"}}" +
            "]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getToolCalls(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getToolCalls().get(0).getArguments(), is("plain_string"));
    }

    @Test
    public void shouldDecodeEmptyMessagesArray() {
        ParsedConversation parsed = codec.decode(request().withBody("{\"messages\":[]}"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldDeclareNdjsonStreamingFormat() {
        assertThat(codec.streamingFormat(), is(StreamingFormat.NDJSON));
    }

    @Test
    public void streamingRoundTripPreservesTextContent() throws Exception {
        Completion input = completion()
            .withText("Hello from Ollama!")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(4));

        List<SseEvent> events = codec.encodeStreaming(input, "llama3.1", null);

        // Concatenate content from all chunks (including final)
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
            String content = chunk.get("message").get("content").asText("");
            concatenated.append(content);
        }
        assertThat(concatenated.toString(), is("Hello from Ollama!"));
    }

    @Test
    public void streamingChunksAreAllValidJson() throws Exception {
        Completion input = completion()
            .withText("Multi word text here")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(4));

        List<SseEvent> events = codec.encodeStreaming(input, "llama3.1", null);

        for (int i = 0; i < events.size(); i++) {
            String data = events.get(i).getData();
            // Every chunk must be valid JSON (NDJSON requirement)
            JsonNode parsed = OBJECT_MAPPER.readTree(data);
            assertThat("chunk " + i + " must be a JSON object", parsed.isObject(), is(true));
            assertThat("chunk " + i + " must have model field", parsed.has("model"), is(true));
        }
    }

    @Test
    public void shouldHandleNullUsageGracefully() throws Exception {
        // No usage set at all
        Completion input = completion().withText("test");
        HttpResponse response = codec.encode(input, "llama3.1");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("prompt_eval_count").asInt(), is(0));
        assertThat(root.get("eval_count").asInt(), is(0));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowUnsupportedForEmbeddings() {
        codec.encodeEmbedding(EmbeddingResponse.embedding(), "test");
    }

    @Test
    public void shouldDecodeUnknownRoleAsUser() {
        String body = "{\"messages\":[{\"role\":\"unknown_role\",\"content\":\"Hi\"}]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
    }
}
