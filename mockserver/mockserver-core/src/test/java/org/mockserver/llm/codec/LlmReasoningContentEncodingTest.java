package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;

/**
 * Verifies that codecs encode the optional reasoning/"thinking" content
 * ({@link Completion#withReasoningText(String)}) as a provider-correct content
 * block, prepended before the visible text, on both the non-streaming and
 * streaming paths — and that the visible text is unaffected and the block is
 * absent when reasoning is not set.
 */
public class LlmReasoningContentEncodingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JsonNode parse(HttpResponse response) throws Exception {
        return OBJECT_MAPPER.readTree(response.getBodyAsString());
    }

    // -------------------------------------------------------------------
    // Anthropic — leading {"type":"thinking", ...} content block
    // -------------------------------------------------------------------

    @Test
    public void anthropicPrependsThinkingBlockWithSignature() throws Exception {
        Completion completion = completion()
            .withReasoningText("Let me work through this.")
            .withReasoningSignature("sig-abc")
            .withText("The answer is 42.");

        JsonNode content = parse(new AnthropicCodec().encode(completion, "claude-sonnet-4-20250514")).get("content");

        assertThat(content.size(), is(2));
        assertThat(content.get(0).get("type").asText(), is("thinking"));
        assertThat(content.get(0).get("thinking").asText(), is("Let me work through this."));
        assertThat(content.get(0).get("signature").asText(), is("sig-abc"));
        // text block follows the thinking block
        assertThat(content.get(1).get("type").asText(), is("text"));
        assertThat(content.get(1).get("text").asText(), is("The answer is 42."));
    }

    @Test
    public void anthropicThinkingBlockOmitsSignatureWhenUnset() throws Exception {
        Completion completion = completion()
            .withReasoningText("reasoning")
            .withText("answer");

        JsonNode content = parse(new AnthropicCodec().encode(completion, "claude-sonnet-4-20250514")).get("content");

        assertThat(content.get(0).get("type").asText(), is("thinking"));
        assertThat(content.get(0).has("signature"), is(false));
    }

    @Test
    public void anthropicHasNoThinkingBlockWhenReasoningUnset() throws Exception {
        Completion completion = completion().withText("answer");

        JsonNode content = parse(new AnthropicCodec().encode(completion, "claude-sonnet-4-20250514")).get("content");

        assertThat(content.size(), is(1));
        assertThat(content.get(0).get("type").asText(), is("text"));
    }

    @Test
    public void anthropicStreamingEmitsThinkingDeltaAndSignatureBeforeText() throws Exception {
        Completion completion = completion()
            .withReasoningText("step by step")
            .withReasoningSignature("sig-1")
            .withText("done");

        List<SseEvent> events = new AnthropicCodec().encodeStreaming(completion, "claude-sonnet-4-20250514", null);

        // message_start, then thinking content_block_start at index 0
        assertThat(events.get(0).getEvent(), is("message_start"));
        JsonNode thinkingStart = OBJECT_MAPPER.readTree(events.get(1).getData());
        assertThat(events.get(1).getEvent(), is("content_block_start"));
        assertThat(thinkingStart.get("content_block").get("type").asText(), is("thinking"));
        assertThat(thinkingStart.get("index").asInt(), is(0));

        // a thinking_delta and a signature_delta are present
        boolean sawThinkingDelta = false;
        boolean sawSignatureDelta = false;
        Integer textBlockIndex = null;
        for (SseEvent event : events) {
            if (!"content_block_delta".equals(event.getEvent())
                && !"content_block_start".equals(event.getEvent())) {
                continue;
            }
            JsonNode data = OBJECT_MAPPER.readTree(event.getData());
            if ("content_block_delta".equals(event.getEvent())) {
                String deltaType = data.get("delta").get("type").asText();
                if ("thinking_delta".equals(deltaType)) {
                    sawThinkingDelta = true;
                    assertThat(data.get("delta").get("thinking").asText(), is("step by step"));
                } else if ("signature_delta".equals(deltaType)) {
                    sawSignatureDelta = true;
                    assertThat(data.get("delta").get("signature").asText(), is("sig-1"));
                }
            } else if ("content_block_start".equals(event.getEvent())
                && "text".equals(data.get("content_block").get("type").asText())) {
                textBlockIndex = data.get("index").asInt();
            }
        }
        assertThat(sawThinkingDelta, is(true));
        assertThat(sawSignatureDelta, is(true));
        // the text block is at index 1 (after the thinking block at index 0)
        assertThat(textBlockIndex, is(1));
    }

    @Test
    public void anthropicStreamingHasNoThinkingEventsWhenReasoningUnset() throws Exception {
        Completion completion = completion().withText("done");

        List<SseEvent> events = new AnthropicCodec().encodeStreaming(completion, "claude-sonnet-4-20250514", null);

        for (SseEvent event : events) {
            if ("content_block_delta".equals(event.getEvent())) {
                String deltaType = OBJECT_MAPPER.readTree(event.getData()).get("delta").get("type").asText();
                assertThat(deltaType, not(is("thinking_delta")));
                assertThat(deltaType, not(is("signature_delta")));
            }
        }
    }

    // -------------------------------------------------------------------
    // OpenAI Responses — leading {"type":"reasoning", "summary":[...]} item
    // -------------------------------------------------------------------

    @Test
    public void openAiResponsesPrependsReasoningOutputItem() throws Exception {
        Completion completion = completion()
            .withReasoningText("internal thoughts")
            .withText("final answer");

        JsonNode output = parse(new OpenAiResponsesCodec().encode(completion, "gpt-4o")).get("output");

        assertThat(output.get(0).get("type").asText(), is("reasoning"));
        assertThat(output.get(0).get("summary").get(0).get("type").asText(), is("summary_text"));
        assertThat(output.get(0).get("summary").get(0).get("text").asText(), is("internal thoughts"));
        assertThat(output.get(1).get("type").asText(), is("message"));
    }

    @Test
    public void openAiResponsesHasNoReasoningItemWhenUnset() throws Exception {
        Completion completion = completion().withText("final answer");

        JsonNode output = parse(new OpenAiResponsesCodec().encode(completion, "gpt-4o")).get("output");

        assertThat(output.get(0).get("type").asText(), is("message"));
    }

    @Test
    public void openAiResponsesStreamingEmitsReasoningSummaryEvents() throws Exception {
        Completion completion = completion()
            .withReasoningText("internal thoughts")
            .withText("final answer");

        List<SseEvent> events = new OpenAiResponsesCodec().encodeStreaming(completion, "gpt-4o", null);

        boolean sawSummaryDelta = false;
        for (SseEvent event : events) {
            if ("response.reasoning_summary_text.delta".equals(event.getEvent())) {
                sawSummaryDelta = true;
                assertThat(OBJECT_MAPPER.readTree(event.getData()).get("delta").asText(), is("internal thoughts"));
            }
        }
        assertThat(sawSummaryDelta, is(true));
    }

    // -------------------------------------------------------------------
    // Gemini — {"text":"...","thought":true} part before the text part
    // -------------------------------------------------------------------

    @Test
    public void geminiPrependsThoughtPart() throws Exception {
        Completion completion = completion()
            .withReasoningText("thinking out loud")
            .withText("the result");

        JsonNode parts = parse(new GeminiCodec().encode(completion, "gemini-1.5-pro"))
            .get("candidates").get(0).get("content").get("parts");

        assertThat(parts.get(0).get("thought").asBoolean(), is(true));
        assertThat(parts.get(0).get("text").asText(), is("thinking out loud"));
        assertThat(parts.get(1).get("text").asText(), is("the result"));
        assertThat(parts.get(1).has("thought"), is(false));
    }

    @Test
    public void geminiHasNoThoughtPartWhenUnset() throws Exception {
        Completion completion = completion().withText("the result");

        JsonNode parts = parse(new GeminiCodec().encode(completion, "gemini-1.5-pro"))
            .get("candidates").get(0).get("content").get("parts");

        assertThat(parts.size(), is(1));
        assertThat(parts.get(0).has("thought"), is(false));
    }

    @Test
    public void geminiStreamingEmitsThoughtChunkBeforeText() throws Exception {
        Completion completion = completion()
            .withReasoningText("thinking")
            .withText("result");

        List<SseEvent> events = new GeminiCodec().encodeStreaming(completion, "gemini-1.5-pro", null);

        JsonNode firstPart = OBJECT_MAPPER.readTree(events.get(0).getData())
            .get("candidates").get(0).get("content").get("parts").get(0);
        assertThat(firstPart.get("thought").asBoolean(), is(true));
        assertThat(firstPart.get("text").asText(), is("thinking"));
    }

    // -------------------------------------------------------------------
    // Ollama — message.thinking (no native cached/reasoning token field)
    // -------------------------------------------------------------------

    @Test
    public void ollamaEncodesThinkingOnMessage() throws Exception {
        Completion completion = completion()
            .withReasoningText("ollama reasoning")
            .withText("ollama answer");

        JsonNode message = parse(new OllamaCodec().encode(completion, "llama3.1")).get("message");

        assertThat(message.get("content").asText(), is("ollama answer"));
        assertThat(message.get("thinking").asText(), is("ollama reasoning"));
    }

    @Test
    public void ollamaOmitsThinkingWhenUnset() throws Exception {
        Completion completion = completion().withText("ollama answer");

        JsonNode message = parse(new OllamaCodec().encode(completion, "llama3.1")).get("message");

        assertThat(message.has("thinking"), is(false));
    }

    @Test
    public void ollamaStreamingEmitsThinkingChunkBeforeContent() throws Exception {
        Completion completion = completion()
            .withReasoningText("ollama reasoning")
            .withText("answer");

        List<SseEvent> events = new OllamaCodec().encodeStreaming(completion, "llama3.1", null);

        JsonNode firstMessage = OBJECT_MAPPER.readTree(events.get(0).getData()).get("message");
        assertThat(firstMessage.get("thinking").asText(), is("ollama reasoning"));
    }
}
