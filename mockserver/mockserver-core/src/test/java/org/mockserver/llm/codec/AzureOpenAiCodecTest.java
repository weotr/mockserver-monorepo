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

/**
 * Tests for the Azure OpenAI codec. Since it delegates to OpenAiChatCompletionsCodec,
 * these tests primarily verify the delegation works and that provider/version metadata
 * is correct.
 */
public class AzureOpenAiCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AzureOpenAiCodec codec = new AzureOpenAiCodec();

    // --- Provider & Version ---

    @Test
    public void shouldReturnAzureOpenAiProviderAndVersion() {
        assertThat(codec.provider(), is(Provider.AZURE_OPENAI));
        assertThat(codec.apiVersion(), is("2024-10-21"));
    }

    // --- Encode Non-Streaming (OpenAI format via delegation) ---

    @Test
    public void shouldEncodeTextCompletionInOpenAiFormat() throws Exception {
        Completion completion = completion()
            .withText("Hello from Azure.")
            .withUsage(Usage.usage().withInputTokens(15).withOutputTokens(3));

        HttpResponse response = codec.encode(completion, "gpt-4o");

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("id").asText(), startsWith("chatcmpl-"));
        assertThat(root.get("object").asText(), is("chat.completion"));
        assertThat(root.get("model").asText(), is("gpt-4o"));

        JsonNode choices = root.get("choices");
        assertThat(choices.size(), is(1));
        assertThat(choices.get(0).get("message").get("role").asText(), is("assistant"));
        assertThat(choices.get(0).get("message").get("content").asText(), is("Hello from Azure."));
        assertThat(choices.get(0).get("finish_reason").asText(), is("stop"));

        JsonNode usage = root.get("usage");
        assertThat(usage.get("prompt_tokens").asInt(), is(15));
        assertThat(usage.get("completion_tokens").asInt(), is(3));
        assertThat(usage.get("total_tokens").asInt(), is(18));
    }

    @Test
    public void shouldEncodeToolCallsInOpenAiFormat() throws Exception {
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"));

        HttpResponse response = codec.encode(completion, "gpt-4o");

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode message = root.get("choices").get(0).get("message");
        assertThat(message.get("content").isNull(), is(true));
        assertThat(message.get("tool_calls").size(), is(1));
        assertThat(message.get("tool_calls").get(0).get("function").get("name").asText(), is("search"));
        assertThat(root.get("choices").get(0).get("finish_reason").asText(), is("tool_calls"));
    }

    @Test
    public void shouldMapFinishReasons() throws Exception {
        Completion completion = completion().withText("test").withStopReason("end_turn");
        HttpResponse response = codec.encode(completion, "gpt-4o");
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("choices").get(0).get("finish_reason").asText(), is("stop"));
    }

    @Test
    public void shouldSetContentTypeHeader() throws Exception {
        Completion completion = completion().withText("test");
        HttpResponse response = codec.encode(completion, "gpt-4o");
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
    }

    // --- Encode Streaming (OpenAI SSE format via delegation) ---

    @Test
    public void shouldProduceOpenAiStreamingChunks() throws Exception {
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // Same as OpenAI: first chunk (role), N text chunks, final chunk (finish_reason), [DONE]
        assertThat(events.size(), is(greaterThanOrEqualTo(5)));

        // First chunk: role-only
        JsonNode first = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(first.get("object").asText(), is("chat.completion.chunk"));

        // Last event: [DONE]
        assertThat(events.get(events.size() - 1).getData(), is("[DONE]"));
    }

    // --- Encode Embedding (OpenAI format via delegation) ---

    @Test
    public void shouldEncodeEmbeddingInOpenAiFormat() throws Exception {
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(4)
            .withDeterministicFromInput(true)
            .withSeed(42L);

        HttpResponse response = codec.encodeEmbedding(embedding, "test input");

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("object").asText(), is("list"));
        assertThat(root.get("data").get(0).get("embedding").size(), is(4));
    }

    // --- Decode (OpenAI messages format via delegation) ---

    @Test
    public void shouldDecodeOpenAiFormatMessages() {
        HttpRequest request = request()
            .withBody("{\n" +
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
    public void shouldDecodeToolCallAndToolResult() {
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Search\"},\n" +
                "    {\"role\": \"assistant\", \"content\": null, \"tool_calls\": [\n" +
                "      {\"id\": \"call_1\", \"type\": \"function\", \"function\": {\"name\": \"search\", \"arguments\": \"{}\"}}\n" +
                "    ]},\n" +
                "    {\"role\": \"tool\", \"tool_call_id\": \"call_1\", \"content\": \"result\"}\n" +
                "  ]\n" +
                "}");

        ParsedConversation parsed = codec.decode(request);

        assertThat(parsed.getMessages(), hasSize(3));
        assertThat(parsed.getMessages().get(1).getToolCalls(), hasSize(1));
        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(parsed.getMessages().get(2).getToolResults(), hasEntry("call_1", "result"));
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
}
