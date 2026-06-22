package org.mockserver.llm.client;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

public class LlmProviderSnifferTest {

    @After
    public void resetConfig() {
        ConfigurationProperties.llmProvider("");
        ConfigurationProperties.llmBaseUrl("");
    }

    // --- Well-known host detection ---

    @Test
    public void detectsOpenAiByHost() {
        assertThat(LlmProviderSniffer.sniffByHost("api.openai.com"), is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void detectsOpenAiCaseInsensitive() {
        assertThat(LlmProviderSniffer.sniffByHost("API.OPENAI.COM"), is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void detectsOpenAiChatCompletionsPath() {
        // api.openai.com with /v1/chat/completions → OPENAI (not OPENAI_RESPONSES)
        assertThat(LlmProviderSniffer.sniffByHostAndPath("api.openai.com", "/v1/chat/completions"),
            is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void detectsOpenAiResponsesApiByPath() {
        // api.openai.com with /v1/responses → OPENAI_RESPONSES
        assertThat(LlmProviderSniffer.sniffByHostAndPath("api.openai.com", "/v1/responses"),
            is(Optional.of(Provider.OPENAI_RESPONSES)));
    }

    @Test
    public void detectsOpenAiResponsesApiWithTrailingId() {
        // api.openai.com with /v1/responses/resp_abc123 → OPENAI_RESPONSES
        assertThat(LlmProviderSniffer.sniffByHostAndPath("api.openai.com", "/v1/responses/resp_abc123"),
            is(Optional.of(Provider.OPENAI_RESPONSES)));
    }

    @Test
    public void detectsAzureOpenAiByWildcardHost() {
        assertThat(LlmProviderSniffer.sniffByHost("my-deployment.openai.azure.com"), is(Optional.of(Provider.AZURE_OPENAI)));
    }

    @Test
    public void detectsAnthropicByHost() {
        assertThat(LlmProviderSniffer.sniffByHost("api.anthropic.com"), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void detectsGeminiByHost() {
        assertThat(LlmProviderSniffer.sniffByHost("generativelanguage.googleapis.com"), is(Optional.of(Provider.GEMINI)));
    }

    @Test
    public void detectsBedrockByHost() {
        assertThat(LlmProviderSniffer.sniffByHost("bedrock-runtime.us-east-1.amazonaws.com"), is(Optional.of(Provider.BEDROCK)));
    }

    @Test
    public void detectsBedrockAlternateRegion() {
        assertThat(LlmProviderSniffer.sniffByHost("bedrock.eu-west-1.amazonaws.com"), is(Optional.of(Provider.BEDROCK)));
    }

    // --- Ollama via configured base URL ---

    @Test
    public void detectsOllamaViaConfiguredBaseUrl() {
        ConfigurationProperties.llmBaseUrl("http://localhost:11434");
        assertThat(LlmProviderSniffer.sniffByHost("localhost"), is(Optional.of(Provider.OLLAMA)));
    }

    @Test
    public void detectsOllamaViaConfiguredBaseUrlWithPath() {
        ConfigurationProperties.llmBaseUrl("http://my-ollama-host:11434/v1");
        assertThat(LlmProviderSniffer.sniffByHost("my-ollama-host"), is(Optional.of(Provider.OLLAMA)));
    }

    // --- Fallback to configured provider (path-gated) ---

    @Test
    public void fallsBackToConfiguredProviderWithLlmPath() {
        ConfigurationProperties.llmProvider("ANTHROPIC");
        assertThat(LlmProviderSniffer.sniffByHostAndPath("unknown-host.example.com", "/v1/chat/completions"),
            is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void fallsBackToConfiguredProviderCaseInsensitiveWithLlmPath() {
        ConfigurationProperties.llmProvider("openai");
        assertThat(LlmProviderSniffer.sniffByHostAndPath("unknown-host.example.com", "/v1/messages"),
            is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void configuredProviderFallbackRequiresLlmPath() {
        // COR-05: configured provider should NOT match when path is not LLM-like
        ConfigurationProperties.llmProvider("ANTHROPIC");
        assertThat(LlmProviderSniffer.sniffByHostAndPath("unknown-host.example.com", "/api/users"),
            is(Optional.empty()));
    }

    @Test
    public void configuredProviderFallbackWithNullPath() {
        // COR-05: configured provider should NOT match when path is null
        ConfigurationProperties.llmProvider("OPENAI");
        assertThat(LlmProviderSniffer.sniffByHost("unknown-host.example.com"), is(Optional.empty()));
    }

    @Test
    public void configuredProviderFallbackMatchesVariousLlmPaths() {
        ConfigurationProperties.llmProvider("OPENAI");
        // /completions
        assertThat(LlmProviderSniffer.sniffByHostAndPath("custom-llm.example.com", "/v1/completions"),
            is(Optional.of(Provider.OPENAI)));
        // /embeddings
        assertThat(LlmProviderSniffer.sniffByHostAndPath("custom-llm.example.com", "/v1/embeddings"),
            is(Optional.of(Provider.OPENAI)));
        // :generatecontent (Gemini-style)
        assertThat(LlmProviderSniffer.sniffByHostAndPath("custom-llm.example.com", "/v1/models/gemini:generateContent"),
            is(Optional.of(Provider.OPENAI)));
        // /api/generate (Ollama-style)
        assertThat(LlmProviderSniffer.sniffByHostAndPath("custom-llm.example.com", "/api/generate"),
            is(Optional.of(Provider.OPENAI)));
        // /api/chat (Ollama-style)
        assertThat(LlmProviderSniffer.sniffByHostAndPath("custom-llm.example.com", "/api/chat"),
            is(Optional.of(Provider.OPENAI)));
        // /responses
        assertThat(LlmProviderSniffer.sniffByHostAndPath("custom-llm.example.com", "/v1/responses"),
            is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void returnsEmptyWhenNoProviderConfigured() {
        assertThat(LlmProviderSniffer.sniffByHostAndPath("unknown-host.example.com", "/v1/chat/completions"),
            is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForNullHost() {
        assertThat(LlmProviderSniffer.sniffByHost(null), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForEmptyHost() {
        assertThat(LlmProviderSniffer.sniffByHost(""), is(Optional.empty()));
    }

    @Test
    public void returnsEmptyForInvalidConfiguredProvider() {
        ConfigurationProperties.llmProvider("NOT_A_REAL_PROVIDER");
        assertThat(LlmProviderSniffer.sniffByHostAndPath("unknown-host.example.com", "/v1/chat/completions"),
            is(Optional.empty()));
    }

    // --- sniff(HttpRequest) with socket address ---

    @Test
    public void sniffsProviderFromRequestSocketAddress() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withSocketAddress(true, "api.openai.com", 443);
        assertThat(LlmProviderSniffer.sniff(request), is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void sniffsOpenAiResponsesApiFromRequest() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/v1/responses")
            .withSocketAddress(true, "api.openai.com", 443);
        assertThat(LlmProviderSniffer.sniff(request), is(Optional.of(Provider.OPENAI_RESPONSES)));
    }

    @Test
    public void sniffsProviderFromRequestHostHeader() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/v1/messages")
            .withHeader("Host", "api.anthropic.com");
        assertThat(LlmProviderSniffer.sniff(request), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void sniffsProviderFromHostHeaderWithPort() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/v1/messages")
            .withHeader("Host", "api.anthropic.com:443");
        assertThat(LlmProviderSniffer.sniff(request), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void returnsEmptyForNullRequest() {
        assertThat(LlmProviderSniffer.sniff(null), is(Optional.empty()));
    }

    @Test
    public void sniffReturnsEmptyForUnknownHostAndNonLlmPathWithConfiguredProvider() {
        // COR-05: configured provider + unknown host + non-LLM path → empty
        ConfigurationProperties.llmProvider("OPENAI");
        HttpRequest request = request()
            .withMethod("GET")
            .withPath("/api/users")
            .withSocketAddress(true, "example.com", 443);
        assertThat(LlmProviderSniffer.sniff(request), is(Optional.empty()));
    }

    @Test
    public void sniffReturnsProviderForUnknownHostAndLlmPathWithConfiguredProvider() {
        // COR-05: configured provider + unknown host + LLM path → configured provider
        ConfigurationProperties.llmProvider("ANTHROPIC");
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withSocketAddress(true, "my-custom-llm.internal", 443);
        assertThat(LlmProviderSniffer.sniff(request), is(Optional.of(Provider.ANTHROPIC)));
    }

    // --- Model extraction ---

    @Test
    public void extractsModelFromOpenAiResponse() {
        org.mockserver.model.HttpResponse response = org.mockserver.model.HttpResponse.response()
            .withBody("{\"id\":\"chatcmpl-123\",\"model\":\"gpt-4o\",\"choices\":[{\"message\":{\"content\":\"hello\"}}]}");
        assertThat(LlmProviderSniffer.extractModelFromResponse(response), is("gpt-4o"));
    }

    @Test
    public void extractsModelFromAnthropicResponse() {
        org.mockserver.model.HttpResponse response = org.mockserver.model.HttpResponse.response()
            .withBody("{\"model\":\"claude-3-5-sonnet-20241022\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}");
        assertThat(LlmProviderSniffer.extractModelFromResponse(response), is("claude-3-5-sonnet-20241022"));
    }

    @Test
    public void returnsNullForNonJsonResponse() {
        org.mockserver.model.HttpResponse response = org.mockserver.model.HttpResponse.response()
            .withBody("not json");
        assertThat(LlmProviderSniffer.extractModelFromResponse(response) == null, is(true));
    }

    @Test
    public void returnsNullForResponseWithNoModel() {
        org.mockserver.model.HttpResponse response = org.mockserver.model.HttpResponse.response()
            .withBody("{\"data\":\"something\"}");
        assertThat(LlmProviderSniffer.extractModelFromResponse(response) == null, is(true));
    }

    @Test
    public void returnsNullForNullResponse() {
        assertThat(LlmProviderSniffer.extractModelFromResponse(null) == null, is(true));
    }

    @Test
    public void extractsModelFromRequestBody() {
        HttpRequest request = request()
            .withBody("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(LlmProviderSniffer.extractModelFromRequest(request), is("gpt-4o-mini"));
    }

    @Test
    public void returnsNullForRequestWithNoModel() {
        HttpRequest request = request()
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(LlmProviderSniffer.extractModelFromRequest(request) == null, is(true));
    }

    // --- Offline analysis detection (detectForAnalysis / sniffByPath) ---
    // Recognises MOCKED LLM traffic on localhost that host-based sniff() misses,
    // so the optimisation report matches the Sessions view.

    @Test
    public void sniffByPathDetectsAnthropicMessages() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/v1/messages")), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void sniffByPathDetectsOpenAiChatCompletions() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/v1/chat/completions")), is(Optional.of(Provider.OPENAI)));
    }

    @Test
    public void sniffByPathDetectsOpenAiResponsesBeforeChatCompletions() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/v1/responses")), is(Optional.of(Provider.OPENAI_RESPONSES)));
    }

    @Test
    public void sniffByPathDetectsAzureBeforeGenericOpenAi() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/openai/deployments/gpt4o/chat/completions")),
            is(Optional.of(Provider.AZURE_OPENAI)));
    }

    @Test
    public void sniffByPathDetectsBedrockAnthropic() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/model/anthropic.claude-3/invoke")),
            is(Optional.of(Provider.BEDROCK)));
    }

    @Test
    public void sniffByPathDetectsGemini() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/v1beta/models/gemini-2.0-flash:generateContent")),
            is(Optional.of(Provider.GEMINI)));
    }

    @Test
    public void sniffByPathDetectsOllamaChat() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/api/chat")), is(Optional.of(Provider.OLLAMA)));
    }

    @Test
    public void sniffByPathReturnsEmptyForNonLlmPath() {
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/api/chatbot")), is(Optional.empty()));
        assertThat(LlmProviderSniffer.sniffByPath(request().withPath("/health")), is(Optional.empty()));
    }

    @Test
    public void detectForAnalysisRecognisesMockedLocalhostTraffic() {
        // No provider configured, host is localhost (mocked LLM response). sniff() returns
        // empty; detectForAnalysis falls back to the path shape.
        HttpRequest mocked = request().withHeader("Host", "localhost:1080").withPath("/v1/messages");
        assertThat(LlmProviderSniffer.sniff(mocked), is(Optional.empty()));
        assertThat(LlmProviderSniffer.detectForAnalysis(mocked), is(Optional.of(Provider.ANTHROPIC)));
    }

    @Test
    public void detectForAnalysisPrefersHostWhenKnown() {
        HttpRequest req = request().withHeader("Host", "api.openai.com").withPath("/v1/chat/completions");
        assertThat(LlmProviderSniffer.detectForAnalysis(req), is(Optional.of(Provider.OPENAI)));
    }
}
