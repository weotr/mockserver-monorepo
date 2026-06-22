package org.mockserver.llm.client;

import org.junit.Test;
import org.mockserver.model.Completion;
import org.mockserver.model.Usage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests that cached-input and reasoning token usage fields decode
 * from each provider's response usage shape into {@link Usage}, and stay absent
 * (back-compatible) when the provider doesn't report them.
 */
public class CachedReasoningUsageTest {

    @Test
    public void shouldDecodeAnthropicCacheTokens() {
        Completion completion = new AnthropicLlmClient().parseCompletionResponse(response()
            .withBody("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]," +
                "\"usage\":{\"input_tokens\":100,\"output_tokens\":20," +
                "\"cache_read_input_tokens\":80,\"cache_creation_input_tokens\":12}}"));

        Usage usage = completion.getUsage();
        assertThat(usage.getInputTokens(), is(100));
        assertThat(usage.getOutputTokens(), is(20));
        assertThat(usage.getCachedInputTokens(), is(80));
        assertThat(usage.getCacheCreationTokens(), is(12));
        assertThat(usage.getReasoningTokens(), is(nullValue()));
    }

    @Test
    public void shouldDecodeOpenAiCachedAndReasoningTokens() {
        Completion completion = new OpenAiLlmClient().parseCompletionResponse(response()
            .withBody("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":30," +
                "\"prompt_tokens_details\":{\"cached_tokens\":40}," +
                "\"completion_tokens_details\":{\"reasoning_tokens\":25}}}"));

        Usage usage = completion.getUsage();
        assertThat(usage.getInputTokens(), is(50));
        assertThat(usage.getOutputTokens(), is(30));
        assertThat(usage.getCachedInputTokens(), is(40));
        assertThat(usage.getReasoningTokens(), is(25));
        assertThat(usage.getCacheCreationTokens(), is(nullValue()));
    }

    @Test
    public void shouldDecodeOpenAiResponsesCachedAndReasoningTokens() {
        Completion completion = new OpenAiResponsesLlmClient().parseCompletionResponse(response()
            .withBody("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]," +
                "\"usage\":{\"input_tokens\":60,\"output_tokens\":40," +
                "\"input_tokens_details\":{\"cached_tokens\":35}," +
                "\"output_tokens_details\":{\"reasoning_tokens\":18}}}"));

        Usage usage = completion.getUsage();
        assertThat(usage.getCachedInputTokens(), is(35));
        assertThat(usage.getReasoningTokens(), is(18));
    }

    @Test
    public void shouldDecodeGeminiCachedAndThinkingTokens() {
        Completion completion = new GeminiLlmClient().parseCompletionResponse(response()
            .withBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}],\"role\":\"model\"}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":70,\"candidatesTokenCount\":15," +
                "\"cachedContentTokenCount\":50,\"thoughtsTokenCount\":9}}"));

        Usage usage = completion.getUsage();
        assertThat(usage.getInputTokens(), is(70));
        assertThat(usage.getCachedInputTokens(), is(50));
        assertThat(usage.getReasoningTokens(), is(9));
    }

    @Test
    public void shouldLeaveNewFieldsAbsentWhenNotReported() {
        // back-compat: a response with only the baseline counts decodes with the
        // new fields null, i.e. unchanged behaviour
        Completion completion = new OpenAiLlmClient().parseCompletionResponse(response()
            .withBody("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}"));

        Usage usage = completion.getUsage();
        assertThat(usage.getCachedInputTokens(), is(nullValue()));
        assertThat(usage.getCacheCreationTokens(), is(nullValue()));
        assertThat(usage.getReasoningTokens(), is(nullValue()));
    }
}
