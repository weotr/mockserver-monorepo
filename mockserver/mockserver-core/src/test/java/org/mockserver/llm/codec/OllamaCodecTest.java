package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;

public class OllamaCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OllamaCodec codec = new OllamaCodec();

    // --- Provider & Version ---

    @Test
    public void shouldReturnOllamaProviderAndVersion() {
        assertThat(codec.provider(), is(Provider.OLLAMA));
        assertThat(codec.apiVersion(), is("ollama-2025"));
    }

    // --- Encode Non-Streaming ---

    @Test
    public void shouldEncodeTextOnlyCompletion() throws Exception {
        Completion completion = completion()
            .withText("The answer is 42.")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        HttpResponse response = codec.encode(completion, "llama3.1");

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("model").asText(), is("llama3.1"));
        assertThat(root.has("created_at"), is(true));
        assertThat(root.get("done").asBoolean(), is(true));

        JsonNode message = root.get("message");
        assertThat(message.get("role").asText(), is("assistant"));
        assertThat(message.get("content").asText(), is("The answer is 42."));
        assertThat(message.has("tool_calls"), is(false));

        assertThat(root.get("prompt_eval_count").asInt(), is(10));
        assertThat(root.get("eval_count").asInt(), is(5));
    }

    @Test
    public void shouldEncodeToolCallWithJsonObjectArguments() throws Exception {
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"foo\"}"));

        HttpResponse response = codec.encode(completion, "llama3.1");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode message = root.get("message");
        assertThat(message.has("tool_calls"), is(true));
        JsonNode toolCalls = message.get("tool_calls");
        assertThat(toolCalls.size(), is(1));
        JsonNode tc = toolCalls.get(0);
        assertThat(tc.get("function").get("name").asText(), is("search"));
        // Ollama uses arguments as a JSON object, not a string
        assertThat(tc.get("function").get("arguments").isObject(), is(true));
        assertThat(tc.get("function").get("arguments").get("q").asText(), is("foo"));
    }

    @Test
    public void shouldEncodeTextAndToolCalls() throws Exception {
        Completion completion = completion()
            .withText("Let me check.")
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"));

        HttpResponse response = codec.encode(completion, "llama3.1");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("message").get("content").asText(), is("Let me check."));
        assertThat(root.get("message").get("tool_calls").size(), is(1));
    }

    @Test
    public void shouldDefaultTokenCountsToZeros() throws Exception {
        Completion completion = completion().withText("test");

        HttpResponse response = codec.encode(completion, "llama3.1");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("prompt_eval_count").asInt(), is(0));
        assertThat(root.get("eval_count").asInt(), is(0));
    }

    @Test
    public void shouldSetContentTypeHeader() throws Exception {
        Completion completion = completion().withText("test");
        HttpResponse response = codec.encode(completion, "llama3.1");
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
    }

    @Test
    public void shouldHandleNullText() throws Exception {
        Completion completion = completion()
            .withToolCall(toolUse("fn").withArguments("{}"));

        HttpResponse response = codec.encode(completion, "llama3.1");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("message").get("content").asText(), is(""));
    }

    @Test
    public void shouldIncludeDurationFields() throws Exception {
        Completion completion = completion().withText("test");

        HttpResponse response = codec.encode(completion, "llama3.1");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.has("total_duration"), is(true));
        assertThat(root.has("load_duration"), is(true));
        assertThat(root.has("prompt_eval_duration"), is(true));
        assertThat(root.has("eval_duration"), is(true));
    }

    // --- Encode Streaming ---

    @Test
    public void shouldProduceStreamingChunksForText() throws Exception {
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(completion, "llama3.1", null);

        // Text chunks + final done chunk
        assertThat(events.size(), is(greaterThanOrEqualTo(2)));

        // All but last should have done: false
        for (int i = 0; i < events.size() - 1; i++) {
            JsonNode chunk = OBJECT_MAPPER.readTree(events.get(i).getData());
            assertThat(chunk.get("done").asBoolean(), is(false));
            assertThat(chunk.get("message").get("role").asText(), is("assistant"));
        }

        // Last should have done: true
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 1).getData());
        assertThat(finalChunk.get("done").asBoolean(), is(true));
        assertThat(finalChunk.get("prompt_eval_count").asInt(), is(10));
        assertThat(finalChunk.get("eval_count").asInt(), is(5));
    }

    @Test
    public void shouldConcatenateStreamedTextTokens() throws Exception {
        Completion completion = completion().withText("Hello world");

        List<SseEvent> events = codec.encodeStreaming(completion, "llama3.1", null);

        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
            String content = chunk.get("message").get("content").asText("");
            if (!content.isEmpty()) {
                concatenated.append(content);
            }
        }
        assertThat(concatenated.toString(), is("Hello world"));
    }

    @Test
    public void shouldNotUseNamedSseEvents() throws Exception {
        Completion completion = completion().withText("test");

        List<SseEvent> events = codec.encodeStreaming(completion, "llama3.1", null);

        // Ollama uses NDJSON, represented as SSE without event names
        for (SseEvent event : events) {
            assertThat(event.getEvent(), is(nullValue()));
        }
    }

    // --- Decode ---

    @Test
    public void shouldDecodeSimpleTextConversation() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"llama3.1\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Hello\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hello"));
    }

    @Test
    public void shouldDecodeToolCallsOnAssistantMessages() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"llama3.1\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Search\"},\n" +
                "    {\"role\": \"assistant\", \"content\": \"\", \"tool_calls\": [\n" +
                "      {\"function\": {\"name\": \"search\", \"arguments\": {\"q\": \"test\"}}}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(2));
        ParsedMessage assistant = parsed.getMessages().get(1);
        assertThat(assistant.getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(assistant.getToolCalls(), hasSize(1));
        assertThat(assistant.getToolCalls().get(0).getName(), is("search"));
        assertThat(assistant.getToolCalls().get(0).getArguments(), containsString("test"));
    }

    @Test
    public void shouldDecodeToolRoleMessages() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"llama3.1\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"tool\", \"content\": \"tool result data\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(msg.getTextContent(), is("tool result data"));
        assertThat(msg.getToolResults(), hasEntry("", "tool result data"));
    }

    @Test
    public void shouldDecodeSystemMessages() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"system\", \"content\": \"You are helpful.\"},\n" +
                "    {\"role\": \"user\", \"content\": \"Hi\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(2));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.SYSTEM));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("You are helpful."));
    }

    @Test
    public void shouldReturnEmptyForMalformedJson() {
        ParsedConversation parsed = codec.decode(request().withBody("not json"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForNullBody() {
        ParsedConversation parsed = codec.decode(request());
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForNullRequest() {
        ParsedConversation parsed = codec.decode(null);
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForMissingMessages() {
        ParsedConversation parsed = codec.decode(request().withBody("{\"model\": \"llama3.1\"}"));
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowForEmbeddings() {
        codec.encodeEmbedding(EmbeddingResponse.embedding(), "test input");
    }

    @Test
    public void shouldDecodeFullToolCallLoop() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"llama3.1\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"What is the weather?\"},\n" +
                "    {\"role\": \"assistant\", \"content\": \"\", \"tool_calls\": [\n" +
                "      {\"function\": {\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}}}\n" +
                "    ]},\n" +
                "    {\"role\": \"tool\", \"content\": \"18C and sunny\"},\n" +
                "    {\"role\": \"assistant\", \"content\": \"It is 18C and sunny in Paris.\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(4));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(1).getToolCalls(), hasSize(1));
        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(parsed.getMessages().get(3).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(3).getTextContent(), is("It is 18C and sunny in Paris."));
    }

    @Test
    public void shouldEmitToolCallsInStreamingFinalChunk() throws Exception {
        // Regression: previously encodeStreaming() silently dropped tool calls;
        // the final chunk must carry the tool_calls list under message.tool_calls.
        Completion completion = Completion.completion()
            .withToolCall(ToolUse.toolUse("search").withArguments("{\"q\":\"weather\"}"))
            .withToolCall(ToolUse.toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"));

        List<SseEvent> events = codec.encodeStreaming(completion, "llama3.1", null);

        // Last event is the done:true chunk.
        SseEvent finalEvent = events.get(events.size() - 1);
        JsonNode finalChunk = new ObjectMapper().readTree(finalEvent.getData());
        assertThat(finalChunk.path("done").asBoolean(), is(true));

        JsonNode toolCalls = finalChunk.path("message").path("tool_calls");
        assertThat(toolCalls.isArray(), is(true));
        assertThat(toolCalls.size(), is(2));
        assertThat(toolCalls.path(0).path("function").path("name").asText(), is("search"));
        assertThat(toolCalls.path(0).path("function").path("arguments").path("q").asText(), is("weather"));
        assertThat(toolCalls.path(1).path("function").path("name").asText(), is("get_weather"));
        assertThat(toolCalls.path(1).path("function").path("arguments").path("city").asText(), is("Paris"));
    }

    @Test
    public void shouldFallBackForNonObjectToolArgumentsInStreaming() throws Exception {
        // Non-JSON-object arguments are wrapped in {"value": "..."} for safety.
        Completion completion = Completion.completion()
            .withToolCall(ToolUse.toolUse("search").withArguments("raw_string_args"));

        List<SseEvent> events = codec.encodeStreaming(completion, "llama3.1", null);

        SseEvent finalEvent = events.get(events.size() - 1);
        JsonNode finalChunk = new ObjectMapper().readTree(finalEvent.getData());
        JsonNode args = finalChunk.path("message").path("tool_calls").path(0).path("function").path("arguments");
        assertThat(args.isObject(), is(true));
        assertThat(args.path("value").asText(), is("raw_string_args"));
    }
}
