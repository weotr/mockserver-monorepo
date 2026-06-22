package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

public class ProviderDetectorTest {

    // --- path-based detection ---

    @Test
    public void detectsAnthropicFromPath() {
        assertThat(ProviderDetector.detectFromPath("/v1/messages"), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void detectsAnthropicFromFullPath() {
        assertThat(ProviderDetector.detect(request().withPath("/v1/messages")), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void detectsOpenAiFromChatCompletionsPath() {
        assertThat(ProviderDetector.detectFromPath("/v1/chat/completions"), is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void detectsOpenAiResponsesFromPath() {
        assertThat(ProviderDetector.detectFromPath("/v1/responses"), is(Optional.of(Provider.OPENAI_RESPONSES)));
    }

    @Test
    public void detectsAzureOpenAiFromPath() {
        assertThat(ProviderDetector.detectFromPath("/openai/deployments/gpt-4/chat/completions"),
            is(Optional.of(Provider.AZURE_OPENAI)));
    }

    @Test
    public void detectsBedrockFromPath() {
        assertThat(ProviderDetector.detectFromPath("/model/anthropic.claude-3-sonnet-20240229-v1:0/invoke"),
            is(Optional.of(Provider.BEDROCK)));
    }

    @Test
    public void detectsGeminiFromV1BetaPath() {
        assertThat(ProviderDetector.detectFromPath("/v1beta/models/gemini-1.5-pro:generateContent"),
            is(Optional.of(Provider.GEMINI)));
    }

    @Test
    public void detectsGeminiFromV1Path() {
        assertThat(ProviderDetector.detectFromPath("/v1/models/gemini-1.5-pro:streamGenerateContent"),
            is(Optional.of(Provider.GEMINI)));
    }

    @Test
    public void detectsOllamaFromPath() {
        assertThat(ProviderDetector.detectFromPath("/api/chat"), is(Optional.of(Provider.OLLAMA)));
    }

    @Test
    public void detectsOllamaFromPathWithTrailingSlash() {
        assertThat(ProviderDetector.detectFromPath("/api/chat/"), is(Optional.of(Provider.OLLAMA)));
    }

    // --- negative cases ---

    @Test
    public void returnsEmptyForUnknownPath() {
        assertThat(ProviderDetector.detectFromPath("/api/health"), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForNullPath() {
        assertThat(ProviderDetector.detectFromPath(null), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForEmptyPath() {
        assertThat(ProviderDetector.detectFromPath(""), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForNullRequest() {
        assertThat(ProviderDetector.detect(null), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForRequestWithNoPath() {
        assertThat(ProviderDetector.detect(request()), is(Optional.empty()));
    }

    // --- detectFromRequests ---

    @Test
    public void detectsProviderFromListOfRequests() {
        Optional<Provider> result = ProviderDetector.detectFromRequests(Arrays.asList(
            request().withPath("/api/health"),
            request().withPath("/v1/messages")
        ));
        assertThat(result, is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void returnsEmptyForEmptyRequestList() {
        assertThat(ProviderDetector.detectFromRequests(Collections.emptyList()), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForNullRequestList() {
        assertThat(ProviderDetector.detectFromRequests(null), is(Optional.empty()));
    }

    // --- precedence: Azure before generic OpenAI ---

    @Test
    public void azureOpenAiTakesPrecedenceOverGenericOpenAi() {
        // Azure path also contains /chat/completions but should match Azure, not OpenAI
        assertThat(ProviderDetector.detectFromPath("/openai/deployments/my-model/chat/completions"),
            is(Optional.of(Provider.AZURE_OPENAI)));
    }

    // --- precedence: Bedrock before Anthropic ---

    @Test
    public void bedrockPathDoesNotMatchAnthropic() {
        // Bedrock path does NOT contain /v1/messages, so it should match Bedrock
        assertThat(ProviderDetector.detectFromPath("/model/anthropic.claude-v2/invoke"),
            is(Optional.of(Provider.BEDROCK)));
    }
}
