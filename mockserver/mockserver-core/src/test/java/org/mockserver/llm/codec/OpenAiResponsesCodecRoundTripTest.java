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
 * Round-trip and edge-case tests for OpenAiResponsesCodec.
 * Verifies that encoding a Completion to Responses API wire format preserves
 * semantics when decoded, and exercises edge branches: string input, array input
 * with mixed types, function_call items, function_call_output items, null/empty
 * values, and streaming event structure.
 */
public class OpenAiResponsesCodecRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpenAiResponsesCodec codec = new OpenAiResponsesCodec();

    // --- Round-trip: encode then decode preserves semantics ---

    @Test
    public void roundTripTextCompletion() throws Exception {
        Completion input = completion()
            .withText("The Responses API is the new way to interact with OpenAI models.")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(12));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        // Extract the text from the encoded output
        JsonNode output = root.get("output");
        assertThat(output.size(), is(1));
        String encodedText = output.get(0).get("content").get(0).get("text").asText();
        assertThat(encodedText, is("The Responses API is the new way to interact with OpenAI models."));

        // Build a Responses-API-format request with previous output and decode
        String msgId = output.get(0).get("id").asText();
        String requestBody = "{\"input\":[" +
            "{\"role\":\"user\",\"content\":\"Tell me about Responses API\"}," +
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            escapeForJson(encodedText) + "\"}]}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(1).getTextContent(),
            is("The Responses API is the new way to interact with OpenAI models."));
    }

    @Test
    public void roundTripFunctionCallCompletion() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Tokyo\"}"))
            .withToolCall(toolUse("get_news").withArguments("{\"topic\":\"tech\"}"));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());

        JsonNode output = root.get("output");
        assertThat(output.size(), is(2));
        assertThat(output.get(0).get("type").asText(), is("function_call"));
        assertThat(output.get(1).get("type").asText(), is("function_call"));

        String fcId = output.get(0).get("id").asText();
        String fcName = output.get(0).get("name").asText();
        String fcArgs = output.get(0).get("arguments").asText();

        // Build request including function_call and function_call_output
        String requestBody = "{\"input\":[" +
            "{\"type\":\"function_call\",\"id\":\"" + fcId + "\",\"name\":\"" + fcName + "\",\"arguments\":\"" + escapeForJson(fcArgs) + "\"}," +
            "{\"type\":\"function_call_output\",\"call_id\":\"" + fcId + "\",\"output\":\"25C and sunny\"}" +
            "]}";
        ParsedConversation decoded = codec.decode(request().withBody(requestBody));

        assertThat(decoded.getMessages(), hasSize(2));
        // First message: function_call -> ASSISTANT with tool calls
        assertThat(decoded.getMessages().get(0).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(decoded.getMessages().get(0).getToolCalls(), hasSize(1));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(decoded.getMessages().get(0).getToolCalls().get(0).getId(), is(fcId));
        // Second message: function_call_output -> TOOL
        assertThat(decoded.getMessages().get(1).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(decoded.getMessages().get(1).getToolResults(), hasEntry(fcId, "25C and sunny"));
    }

    // --- Edge cases ---

    @Test
    public void shouldDecodeStringInputAsUserMessage() {
        String requestBody = "{\"input\":\"What is the weather?\"}";
        ParsedConversation parsed = codec.decode(request().withBody(requestBody));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("What is the weather?"));
    }

    @Test
    public void shouldDecodeEmptyStringInputAsEmptyUserMessage() {
        String requestBody = "{\"input\":\"\"}";
        ParsedConversation parsed = codec.decode(request().withBody(requestBody));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is(""));
    }

    @Test
    public void shouldDecodeEmptyArrayInputAsEmpty() {
        String requestBody = "{\"input\":[]}";
        ParsedConversation parsed = codec.decode(request().withBody(requestBody));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldHandleNullModelInEncoding() throws Exception {
        Completion input = completion().withText("test");
        HttpResponse response = codec.encode(input, null);
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("model").asText(), is("unknown"));
    }

    @Test
    public void shouldHandleEmptyTextCompletion() throws Exception {
        Completion input = completion().withText("");
        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // Empty text means no output items
        assertThat(root.get("output").size(), is(0));
    }

    @Test
    public void shouldHandleSpecialCharactersInText() throws Exception {
        String specialText = "JSON: {\"key\":\"value\"}\nNewline\tTab \\Backslash";
        Completion input = completion().withText(specialText);

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        String decoded = root.get("output").get(0).get("content").get(0).get("text").asText();
        assertThat(decoded, is(specialText));
    }

    @Test
    public void shouldHandleToolCallWithNullArguments() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("no_args"));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode fc = root.get("output").get(0);
        assertThat(fc.get("type").asText(), is("function_call"));
        assertThat(fc.get("name").asText(), is("no_args"));
        // null arguments should default to "{}"
        assertThat(fc.get("arguments").asText(), is("{}"));
    }

    @Test
    public void shouldHandleTextAndToolCallsTogether() throws Exception {
        Completion input = completion()
            .withText("Let me search for you.")
            .withToolCall(toolUse("search").withArguments("{\"q\":\"weather\"}"));

        HttpResponse response = codec.encode(input, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode output = root.get("output");
        assertThat(output.size(), is(2));
        assertThat(output.get(0).get("type").asText(), is("message"));
        assertThat(output.get(1).get("type").asText(), is("function_call"));
    }

    @Test
    public void shouldDecodeContentArrayWithInputTextType() {
        // Content array with input_text type blocks
        String body = "{\"input\":[" +
            "{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hello"));
    }

    @Test
    public void shouldDecodeContentArrayWithTextType() {
        // Content array with plain "text" type blocks
        String body = "{\"input\":[" +
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}]}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hi"));
    }

    @Test
    public void shouldDecodeInputWithMixedItemTypes() {
        // A request containing a user message, function_call, and function_call_output
        String body = "{\"input\":[" +
            "{\"role\":\"user\",\"content\":\"Search X\"}," +
            "{\"type\":\"function_call\",\"id\":\"fc_1\",\"name\":\"search\",\"arguments\":\"{}\"}," +
            "{\"type\":\"function_call_output\",\"call_id\":\"fc_1\",\"output\":\"result data\"}" +
            "]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(3));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.TOOL));
    }

    @Test
    public void shouldReturnEmptyForNumericInputNode() {
        // input as a number is unsupported
        String body = "{\"input\":42}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldDefaultStreamingFormatToSSE() {
        assertThat(codec.streamingFormat(), is(StreamingFormat.SSE));
    }

    @Test
    public void streamingRoundTripPreservesTextContent() throws Exception {
        Completion input = completion()
            .withText("Streaming from Responses API!")
            .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(input, "gpt-4o", null);

        // Concatenate text deltas
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            if ("response.output_text.delta".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                concatenated.append(data.get("delta").asText());
            }
        }
        assertThat(concatenated.toString(), is("Streaming from Responses API!"));
    }

    @Test
    public void streamingEventSequenceIsCorrect() throws Exception {
        Completion input = completion()
            .withText("Hello")
            .withUsage(Usage.usage().withInputTokens(3).withOutputTokens(1));

        List<SseEvent> events = codec.encodeStreaming(input, "gpt-4o", null);

        // Verify expected event sequence
        assertThat(events.get(0).getEvent(), is("response.created"));
        assertThat(events.get(1).getEvent(), is("response.in_progress"));

        // Somewhere in the middle should be output_item.added and deltas
        boolean foundItemAdded = events.stream()
            .anyMatch(e -> "response.output_item.added".equals(e.getEvent()));
        assertThat(foundItemAdded, is(true));

        // Last event should be response.completed
        assertThat(events.get(events.size() - 1).getEvent(), is("response.completed"));
    }

    @Test
    public void streamingToolCallProducesCorrectEvents() throws Exception {
        Completion input = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"));

        List<SseEvent> events = codec.encodeStreaming(input, "gpt-4o", null);

        // Should have function_call output_item.added and output_item.done events
        boolean foundFcAdded = false;
        boolean foundFcDone = false;
        for (SseEvent event : events) {
            if ("response.output_item.added".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                if ("function_call".equals(data.path("item").path("type").asText())) {
                    foundFcAdded = true;
                }
            }
            if ("response.output_item.done".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                if ("function_call".equals(data.path("item").path("type").asText())) {
                    foundFcDone = true;
                    assertThat(data.path("item").path("name").asText(), is("search"));
                }
            }
        }
        assertThat(foundFcAdded, is(true));
        assertThat(foundFcDone, is(true));
    }

    @Test
    public void shouldGenerateUniqueResponseIds() throws Exception {
        Completion input = completion().withText("test");
        HttpResponse r1 = codec.encode(input, "gpt-4o");
        HttpResponse r2 = codec.encode(input, "gpt-4o");

        JsonNode root1 = OBJECT_MAPPER.readTree(r1.getBodyAsString());
        JsonNode root2 = OBJECT_MAPPER.readTree(r2.getBodyAsString());
        // IDs should be unique across calls
        assertThat(root1.get("id").asText(), is(not(root2.get("id").asText())));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowUnsupportedForEmbeddings() {
        codec.encodeEmbedding(EmbeddingResponse.embedding(), "test");
    }

    @Test
    public void shouldDecodeSystemRoleMessage() {
        String body = "{\"input\":[{\"role\":\"system\",\"content\":\"You are helpful.\"}]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.SYSTEM));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("You are helpful."));
    }

    @Test
    public void shouldDecodeUnknownRoleAsUser() {
        String body = "{\"input\":[{\"role\":\"unknown\",\"content\":\"Hi\"}]}";
        ParsedConversation parsed = codec.decode(request().withBody(body));
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
    }
}
