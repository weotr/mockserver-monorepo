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

public class OpenAiResponsesCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpenAiResponsesCodec codec = new OpenAiResponsesCodec();

    // --- Provider & Version ---

    @Test
    public void shouldReturnOpenAiResponsesProviderAndVersion() {
        assertThat(codec.provider(), is(Provider.OPENAI_RESPONSES));
        assertThat(codec.apiVersion(), is("2025-03"));
    }

    // --- Encode Non-Streaming ---

    @Test
    public void shouldEncodeTextOnlyCompletion() throws Exception {
        Completion completion = completion()
            .withText("The capital of France is Paris.")
            .withUsage(Usage.usage().withInputTokens(42).withOutputTokens(8));

        HttpResponse response = codec.encode(completion, "gpt-4o-2025-03");

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("id").asText(), startsWith("resp_"));
        assertThat(root.get("object").asText(), is("response"));
        assertThat(root.get("status").asText(), is("completed"));
        assertThat(root.get("model").asText(), is("gpt-4o-2025-03"));
        assertThat(root.has("created_at"), is(true));

        JsonNode output = root.get("output");
        assertThat(output.size(), is(1));
        assertThat(output.get(0).get("type").asText(), is("message"));
        assertThat(output.get(0).get("id").asText(), startsWith("msg_"));
        assertThat(output.get(0).get("role").asText(), is("assistant"));
        JsonNode content = output.get(0).get("content");
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("output_text"));
        assertThat(content.get(0).get("text").asText(), is("The capital of France is Paris."));

        JsonNode usage = root.get("usage");
        assertThat(usage.get("input_tokens").asInt(), is(42));
        assertThat(usage.get("output_tokens").asInt(), is(8));
        assertThat(usage.get("total_tokens").asInt(), is(50));
    }

    @Test
    public void shouldEncodeToolCallAsFunction() throws Exception {
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"foo\"}"));

        HttpResponse response = codec.encode(completion, "gpt-4o");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode output = root.get("output");
        assertThat(output.size(), is(1));
        assertThat(output.get(0).get("type").asText(), is("function_call"));
        assertThat(output.get(0).get("id").asText(), startsWith("fc_"));
        assertThat(output.get(0).get("name").asText(), is("search"));
        assertThat(output.get(0).get("arguments").asText(), is("{\"q\":\"foo\"}"));
    }

    @Test
    public void shouldEncodeTextAndToolCalls() throws Exception {
        Completion completion = completion()
            .withText("Let me search.")
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"))
            .withToolCall(toolUse("verify").withArguments("{\"id\":1}"));

        HttpResponse response = codec.encode(completion, "gpt-4o");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode output = root.get("output");
        assertThat(output.size(), is(3));
        assertThat(output.get(0).get("type").asText(), is("message"));
        assertThat(output.get(1).get("type").asText(), is("function_call"));
        assertThat(output.get(1).get("name").asText(), is("search"));
        assertThat(output.get(2).get("type").asText(), is("function_call"));
        assertThat(output.get(2).get("name").asText(), is("verify"));
    }

    @Test
    public void shouldDefaultUsageToZeros() throws Exception {
        Completion completion = completion().withText("Hello");

        HttpResponse response = codec.encode(completion, "gpt-4o");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode usage = root.get("usage");
        assertThat(usage.get("input_tokens").asInt(), is(0));
        assertThat(usage.get("output_tokens").asInt(), is(0));
        assertThat(usage.get("total_tokens").asInt(), is(0));
    }

    @Test
    public void shouldSetContentTypeHeader() throws Exception {
        Completion completion = completion().withText("test");

        HttpResponse response = codec.encode(completion, "gpt-4o");

        assertThat(response.getFirstHeader("content-type"), is("application/json"));
    }

    // --- Encode Streaming ---

    @Test
    public void shouldProduceCorrectStreamingEventSequenceForText() throws Exception {
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // response.created, response.in_progress, output_item.added,
        // N text deltas, output_text.done, output_item.done, response.completed
        assertThat(events.size(), is(greaterThanOrEqualTo(7)));

        assertThat(events.get(0).getEvent(), is("response.created"));
        assertThat(events.get(1).getEvent(), is("response.in_progress"));
        assertThat(events.get(2).getEvent(), is("response.output_item.added"));

        // Text deltas in the middle
        int deltaCount = 0;
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            if ("response.output_text.delta".equals(event.getEvent())) {
                deltaCount++;
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                concatenated.append(data.get("delta").asText());
            }
        }
        assertThat(deltaCount, is(greaterThan(0)));
        assertThat(concatenated.toString(), is("Hello world"));

        // Last event is response.completed
        SseEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent.getEvent(), is("response.completed"));
        JsonNode completedData = OBJECT_MAPPER.readTree(lastEvent.getData());
        assertThat(completedData.get("type").asText(), is("response.completed"));
        assertThat(completedData.get("response").get("status").asText(), is("completed"));
    }

    @Test
    public void shouldProduceToolCallStreamingEvents() throws Exception {
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"));

        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // Should have response.created, response.in_progress,
        // output_item.added (fc), output_item.done (fc), response.completed
        assertThat(events.size(), is(greaterThanOrEqualTo(5)));

        boolean foundFcAdded = false;
        boolean foundFcDone = false;
        for (SseEvent event : events) {
            if ("response.output_item.added".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                if ("function_call".equals(data.path("item").path("type").asText(""))) {
                    foundFcAdded = true;
                }
            }
            if ("response.output_item.done".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                if ("function_call".equals(data.path("item").path("type").asText(""))) {
                    foundFcDone = true;
                    assertThat(data.path("item").path("name").asText(), is("search"));
                }
            }
        }
        assertThat("function_call output_item.added expected", foundFcAdded, is(true));
        assertThat("function_call output_item.done expected", foundFcDone, is(true));
    }

    @Test
    public void shouldIncludeUsageInCompletedEvent() throws Exception {
        Completion completion = completion()
            .withText("Hello")
            .withUsage(Usage.usage().withInputTokens(42).withOutputTokens(8));

        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        SseEvent completed = events.get(events.size() - 1);
        assertThat(completed.getEvent(), is("response.completed"));
        JsonNode data = OBJECT_MAPPER.readTree(completed.getData());
        assertThat(data.get("response").get("usage").get("input_tokens").asInt(), is(42));
        assertThat(data.get("response").get("usage").get("output_tokens").asInt(), is(8));
        assertThat(data.get("response").get("usage").get("total_tokens").asInt(), is(50));
    }

    // --- Decode ---

    @Test
    public void shouldDecodeStringInput() {
        HttpRequest request = request()
            .withBody("{\"input\": \"What is 2+2?\"}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.getRole(), is(ParsedMessage.Role.USER));
        assertThat(msg.getTextContent(), is("What is 2+2?"));
    }

    @Test
    public void shouldDecodeArrayInputWithMessages() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"input\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Hello\"},\n" +
                "    {\"role\": \"assistant\", \"content\": \"Hi there!\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(2));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hello"));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(1).getTextContent(), is("Hi there!"));
    }

    @Test
    public void shouldDecodeFunctionCallOutput() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"input\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Search for X\"},\n" +
                "    {\"type\": \"function_call_output\", \"call_id\": \"fc_abc\", \"output\": \"search result data\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(2));
        ParsedMessage toolMsg = parsed.getMessages().get(1);
        assertThat(toolMsg.getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(toolMsg.getToolResults(), hasEntry("fc_abc", "search result data"));
    }

    @Test
    public void shouldDecodeFunctionCallItem() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"input\": [\n" +
                "    {\"type\": \"function_call\", \"id\": \"fc_xyz\", \"name\": \"search\", \"arguments\": \"{\\\"q\\\":\\\"test\\\"}\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(msg.getToolCalls(), hasSize(1));
        assertThat(msg.getToolCalls().get(0).getName(), is("search"));
        assertThat(msg.getToolCalls().get(0).getId(), is("fc_xyz"));
    }

    @Test
    public void shouldReturnEmptyForMalformedJson() {
        HttpRequest request = request().withBody("not json");
        ParsedConversation parsed = codec.decode(request);
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
    public void shouldReturnEmptyForMissingInput() {
        HttpRequest request = request().withBody("{\"model\": \"gpt-4o\"}");
        ParsedConversation parsed = codec.decode(request);
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowForEmbeddings() {
        codec.encodeEmbedding(EmbeddingResponse.embedding(), "test input");
    }
}
