package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;

public class AnthropicCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AnthropicCodec codec = new AnthropicCodec();

    @Test
    public void shouldReturnAnthropicProviderAndVersion() {
        assertThat(codec.provider(), is(Provider.ANTHROPIC));
        assertThat(codec.apiVersion(), is("2024-10-22"));
    }

    @Test
    public void shouldEncodeTextOnlyCompletion() throws Exception {
        // given
        Completion completion = completion()
            .withText("The capital of France is Paris.")
            .withUsage(Usage.usage().withInputTokens(42).withOutputTokens(8));

        // when
        HttpResponse response = codec.encode(completion, "claude-sonnet-4-20250514");

        // then
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("id").asText(), startsWith("msg_"));
        assertThat(root.get("type").asText(), is("message"));
        assertThat(root.get("role").asText(), is("assistant"));
        assertThat(root.get("model").asText(), is("claude-sonnet-4-20250514"));

        JsonNode content = root.get("content");
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("text"));
        assertThat(content.get(0).get("text").asText(), is("The capital of France is Paris."));

        assertThat(root.get("stop_reason").asText(), is("end_turn"));
        assertThat(root.get("stop_sequence").isNull(), is(true));

        JsonNode usage = root.get("usage");
        assertThat(usage.get("input_tokens").asInt(), is(42));
        assertThat(usage.get("output_tokens").asInt(), is(8));
    }

    @Test
    public void shouldEncodeTextWithOneToolCall() throws Exception {
        // given
        Completion completion = completion()
            .withText("Let me check the weather.")
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
            .withStopReason("tool_use");

        // when
        HttpResponse response = codec.encode(completion, "claude-sonnet-4-20250514");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode content = root.get("content");
        assertThat(content.size(), is(2));

        // text block first
        assertThat(content.get(0).get("type").asText(), is("text"));
        assertThat(content.get(0).get("text").asText(), is("Let me check the weather."));

        // tool_use block second
        assertThat(content.get(1).get("type").asText(), is("tool_use"));
        assertThat(content.get(1).get("id").asText(), startsWith("toolu_"));
        assertThat(content.get(1).get("name").asText(), is("get_weather"));
        assertThat(content.get(1).get("input").get("city").asText(), is("Paris"));

        assertThat(root.get("stop_reason").asText(), is("tool_use"));
    }

    @Test
    public void shouldEncodeToolCallOnlyNoText() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"query\":\"test\"}"));

        // when
        HttpResponse response = codec.encode(completion, "claude-sonnet-4-20250514");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode content = root.get("content");
        // No text block should be present
        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("tool_use"));
        // Default stop_reason with tool calls and no explicit stop reason is "tool_use"
        assertThat(root.get("stop_reason").asText(), is("tool_use"));
    }

    @Test
    public void shouldDefaultStopReasonToEndTurn() throws Exception {
        // given
        Completion completion = completion().withText("Hello");

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("stop_reason").asText(), is("end_turn"));
    }

    @Test
    public void shouldPassThroughCustomStopReason() throws Exception {
        // given
        Completion completion = completion()
            .withText("Hello")
            .withStopReason("max_tokens");

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("stop_reason").asText(), is("max_tokens"));
    }

    @Test
    public void shouldDefaultUsageToZeros() throws Exception {
        // given (no usage set)
        Completion completion = completion().withText("Hello");

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode usage = root.get("usage");
        assertThat(usage.get("input_tokens").asInt(), is(0));
        assertThat(usage.get("output_tokens").asInt(), is(0));
    }

    @Test
    public void shouldEncodeMultipleToolCalls() throws Exception {
        // given
        Completion completion = completion()
            .withText("Let me search and check.")
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"))
            .withToolCall(toolUse("verify").withArguments("{\"id\":123}"))
            .withStopReason("tool_use");

        // when
        HttpResponse response = codec.encode(completion, "claude-sonnet-4-20250514");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode content = root.get("content");
        assertThat(content.size(), is(3));
        assertThat(content.get(0).get("type").asText(), is("text"));
        assertThat(content.get(1).get("type").asText(), is("tool_use"));
        assertThat(content.get(1).get("name").asText(), is("search"));
        assertThat(content.get(2).get("type").asText(), is("tool_use"));
        assertThat(content.get(2).get("name").asText(), is("verify"));
    }

    @Test
    public void shouldSetContentTypeHeader() throws Exception {
        // given
        Completion completion = completion().withText("test");

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
    }

    @Test
    public void shouldReturnStatusCode200() throws Exception {
        // given
        Completion completion = completion().withText("test");

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void shouldParseValidJsonArguments() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("tool").withArguments("{\"key\":\"value\",\"num\":42}"));

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode input = root.get("content").get(0).get("input");
        assertThat(input.isObject(), is(true));
        assertThat(input.get("key").asText(), is("value"));
        assertThat(input.get("num").asInt(), is(42));
    }

    @Test
    public void shouldHandleNonJsonArguments() throws Exception {
        // given — arguments that are not valid JSON are treated as raw string
        Completion completion = completion()
            .withToolCall(toolUse("tool").withArguments("not json"));

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode input = root.get("content").get(0).get("input");
        assertThat(input.asText(), is("not json"));
    }

    @Test
    public void shouldFallBackGracefullyForNonJsonObjectArguments() throws Exception {
        // Gap 3: confirm encode() with arguments="not_a_json_object" falls back
        // gracefully — produces a string input field, not a JSON parse error.
        // This is the documented behaviour from M1.
        Completion completion = completion()
            .withToolCall(toolUse("tool").withArguments("not_a_json_object"));

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then — response is 200 with the raw string as the input value
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode input = root.get("content").get(0).get("input");
        assertThat(input.isTextual(), is(true));
        assertThat(input.asText(), is("not_a_json_object"));
    }

    @Test
    public void shouldFallBackGracefullyForMalformedJsonArguments() throws Exception {
        // Gap 3: structurally invalid JSON (unclosed brace) in arguments
        // should fall back to raw string, not throw
        Completion completion = completion()
            .withToolCall(toolUse("tool").withArguments("{\"unclosed\":"));

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then — response is 200, the malformed string is used as-is
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode input = root.get("content").get(0).get("input");
        assertThat(input.asText(), is("{\"unclosed\":"));
    }

    @Test
    public void shouldHandleNullArguments() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("tool"));

        // when
        HttpResponse response = codec.encode(completion, "test-model");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode input = root.get("content").get(0).get("input");
        assertThat(input.isObject(), is(true));
        assertThat(input.size(), is(0));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowForEmbeddings() {
        codec.encodeEmbedding(EmbeddingResponse.embedding(), "test input");
    }

    @Test
    public void shouldIncludeAnthropicEmbeddingErrorMessage() {
        try {
            codec.encodeEmbedding(EmbeddingResponse.embedding(), "test input");
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage(), is("Anthropic does not expose an embeddings endpoint"));
        }
    }
}
