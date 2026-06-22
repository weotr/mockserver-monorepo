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
 * Round-trip and edge-case tests for GeminiCodec.
 * Verifies that encoding a Completion to Gemini wire format and then
 * decoding a corresponding Gemini-format request preserves semantics.
 * Also exercises edge branches: null model, empty text, special characters,
 * function call/response round-trips, and finish reason mapping.
 */
public class GeminiCodecRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final GeminiCodec codec = new GeminiCodec();

    // --- Round-trip: encode then decode preserves semantics ---

    @Test
    public void roundTripTextCompletion() throws Exception {
        Completion input = completion()
            .withText("Gemini says hello!")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(3));

        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Extract text from Gemini-format response
        String encodedText = root.get("candidates").get(0).get("content")
            .get("parts").get(0).get("text").asText();
        assertThat(encodedText, is("Gemini says hello!"));

        // Build a Gemini-format request with user + model turns and decode
        String requestBody = "{\"contents\":[" +
            "{\"role\":\"user\",\"parts\":[{\"text\":\"Say hello\"}]}," +
            "{\"role\":\"model\",\"parts\":[{\"text\":\"" + encodedText + "\"}]}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(1).getTextContent(), is("Gemini says hello!"));
    }

    @Test
    public void roundTripFunctionCallCompletion() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"));

        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Verify encoded functionCall part
        JsonNode parts = root.get("candidates").get(0).get("content").get("parts");
        assertThat(parts.size(), is(1));
        JsonNode functionCall = parts.get(0).get("functionCall");
        assertThat(functionCall.get("name").asText(), is("get_weather"));
        assertThat(functionCall.get("args").get("city").asText(), is("Paris"));

        // Build request with model functionCall + user functionResponse and decode
        String requestBody = "{\"contents\":[" +
            "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Paris\"}}}]}," +
            "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"get_weather\",\"response\":{\"temp\":\"22C\"}}}]}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(0).getToolCalls(), hasSize(1));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(decoded.getMessages().get(1).getToolResults(), hasKey("get_weather"));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleEmptyTextCompletion() throws Exception {
        Completion input = completion().withText("");
        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // Empty text should still produce valid candidates
        assertThat(root.get("candidates").size(), is(1));
    }

    @Test
    public void shouldHandleSpecialCharactersInText() throws Exception {
        String specialText = "\"double quotes\" and 'single'\nnewline\ttab \\slash Unicode: 中文";
        Completion input = completion().withText(specialText);

        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        String decoded = root.get("candidates").get(0).get("content")
            .get("parts").get(0).get("text").asText();
        assertThat(decoded, is(specialText));
    }

    @Test
    public void shouldHandleNullModelDefaultsToUnknown() throws Exception {
        Completion input = completion().withText("test");
        HttpResponse response = codec.encode(input, null);
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("modelVersion").asText(), is("unknown"));
    }

    @Test
    public void shouldHandleFunctionCallWithNullArguments() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("no_args"));

        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode fc = root.get("candidates").get(0).get("content").get("parts").get(0).get("functionCall");
        assertThat(fc.get("name").asText(), is("no_args"));
        assertThat(fc.get("args").isObject(), is(true));
    }

    @Test
    public void shouldHandleFunctionCallWithNonObjectArgs() throws Exception {
        // Non-JSON-object arguments should be wrapped in {"value":"..."}
        Completion input = completion()
            .withToolCall(toolUse("fn").withArguments("not_json"));

        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode args = root.get("candidates").get(0).get("content").get("parts")
            .get(0).get("functionCall").get("args");
        assertThat(args.isObject(), is(true));
        assertThat(args.get("value").asText(), is("not_json"));
    }

    @Test
    public void shouldHandleMultipleFunctionCallsInParts() throws Exception {
        Completion input = completion()
            .withText("Let me help.")
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"))
            .withToolCall(toolUse("lookup").withArguments("{\"id\":42}"));

        HttpResponse response = codec.encode(input, "gemini-2.0-flash");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode parts = root.get("candidates").get(0).get("content").get("parts");
        // 1 text part + 2 functionCall parts
        assertThat(parts.size(), is(3));
        assertThat(parts.get(0).has("text"), is(true));
        assertThat(parts.get(1).get("functionCall").get("name").asText(), is("search"));
        assertThat(parts.get(2).get("functionCall").get("name").asText(), is("lookup"));
    }

    @Test
    public void shouldMapAllFinishReasonVariants() throws Exception {
        // Test stop_reason mapping for various input values
        String[][] mappings = {
            {"end_turn", "STOP"},
            {"stop", "STOP"},
            {"max_tokens", "MAX_TOKENS"},
            {"length", "MAX_TOKENS"},
            {"tool_use", "STOP"},
            {"tool_calls", "STOP"},
            {"SAFETY", "SAFETY"},
            {"RECITATION", "RECITATION"},
        };

        for (String[] mapping : mappings) {
            Completion input = completion().withText("test").withStopReason(mapping[0]);
            HttpResponse response = codec.encode(input, "gemini-2.0-flash");
            JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
            String finishReason = root.get("candidates").get(0).get("finishReason").asText();
            assertThat("mapping " + mapping[0] + " -> " + mapping[1],
                finishReason, is(mapping[1]));
        }
    }

    @Test
    public void shouldDecodeContentsWithMultipleTextParts() {
        String body = "{\"contents\":[" +
            "{\"role\":\"user\",\"parts\":[" +
            "{\"text\":\"Hello \"}," +
            "{\"text\":\"world\"}" +
            "]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hello world"));
    }

    @Test
    public void shouldDecodeEmptyContentsArray() {
        ParsedConversation parsed = codec.decode(request().withBody("{\"contents\":[]}"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldDecodeContentsWithMissingRole() {
        // Missing role should default to "user"
        String body = "{\"contents\":[{\"parts\":[{\"text\":\"Hi\"}]}]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
    }

    @Test
    public void shouldDecodeContentsWithUnknownRole() {
        // Unknown role should default to USER
        String body = "{\"contents\":[{\"role\":\"unknown\",\"parts\":[{\"text\":\"Hi\"}]}]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
    }

    @Test
    public void shouldDefaultStreamingFormatToSSE() {
        assertThat(codec.streamingFormat(), is(StreamingFormat.SSE));
    }

    @Test
    public void streamingRoundTripPreservesTextContent() throws Exception {
        Completion input = completion()
            .withText("Hello from Gemini!")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(4));

        List<SseEvent> events = codec.encodeStreaming(input, "gemini-2.0-flash", null);

        // Concatenate text from streaming candidates
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
            JsonNode parts = chunk.path("candidates").path(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.has("text")) {
                        concatenated.append(part.get("text").asText());
                    }
                }
            }
        }
        assertThat(concatenated.toString(), is("Hello from Gemini!"));
    }

    @Test
    public void streamingFinalChunkHasFinishReasonAndUsage() throws Exception {
        Completion input = completion()
            .withText("test")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(input, "gemini-2.0-flash", null);

        // Last event should have finishReason and usageMetadata
        JsonNode lastChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 1).getData());
        assertThat(lastChunk.path("candidates").path(0).has("finishReason"), is(true));
        assertThat(lastChunk.has("usageMetadata"), is(true));
        assertThat(lastChunk.get("usageMetadata").get("promptTokenCount").asInt(), is(10));
        assertThat(lastChunk.get("usageMetadata").get("candidatesTokenCount").asInt(), is(5));
        assertThat(lastChunk.get("usageMetadata").get("totalTokenCount").asInt(), is(15));
    }

    @Test
    public void shouldEncodeEmbeddingInGeminiShape() throws Exception {
        HttpResponse response = codec.encodeEmbedding(
            EmbeddingResponse.embedding().withDimensions(4).withDeterministicFromInput(true), "test");
        assertThat(response.getStatusCode(), is(200));
        JsonNode values = OBJECT_MAPPER.readTree(response.getBodyAsString()).get("embedding").get("values");
        assertThat(values.size(), is(4));
    }

    @Test
    public void shouldDecodeFunctionCallWithTextualArgs() {
        // Some clients send args as a string rather than an object
        String body = "{\"contents\":[" +
            "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"fn\",\"args\":\"string_args\"}}]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getToolCalls(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getToolCalls().get(0).getArguments(), is("string_args"));
    }

    @Test
    public void shouldDecodeFunctionResponseWithTextualResponse() {
        // functionResponse where response is a plain string
        String body = "{\"contents\":[" +
            "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"fn\",\"response\":\"plain text result\"}}]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(parsed.getMessages().get(0).getToolResults(), hasEntry("fn", "plain text result"));
    }

    @Test
    public void shouldSynthesizeFunctionCallIds() {
        // Gemini doesn't have tool call IDs; the codec synthesizes them
        String body = "{\"contents\":[" +
            "{\"role\":\"model\",\"parts\":[" +
            "{\"functionCall\":{\"name\":\"alpha\",\"args\":{\"x\":1}}}," +
            "{\"functionCall\":{\"name\":\"beta\",\"args\":{\"y\":2}}}" +
            "]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        List<ToolUse> toolCalls = parsed.getMessages().get(0).getToolCalls();
        assertThat(toolCalls, hasSize(2));
        // IDs should be synthesized and distinct
        assertThat(toolCalls.get(0).getId(), is(notNullValue()));
        assertThat(toolCalls.get(1).getId(), is(notNullValue()));
        assertThat(toolCalls.get(0).getId(), is(not(toolCalls.get(1).getId())));
    }
}
