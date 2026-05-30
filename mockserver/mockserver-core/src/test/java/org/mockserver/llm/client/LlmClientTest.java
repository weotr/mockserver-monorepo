package org.mockserver.llm.client;

import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpResponse.response;

/**
 * Offline build/parse tests for the runtime-LLM clients — no network: each test
 * builds a request from a fixed conversation and parses a canned provider
 * response.
 */
public class LlmClientTest {

    private static ParsedConversation conversation() {
        return ParsedConversation.of(Arrays.asList(
            new ParsedMessage(ParsedMessage.Role.SYSTEM, "You are helpful.", null, null),
            new ParsedMessage(ParsedMessage.Role.USER, "What is the capital of France?", null, null)
        ));
    }

    // --- Ollama (primary backend) ---

    @Test
    public void ollamaBuildsChatRequestWithDeterministicOptions() {
        HttpRequest request = new OllamaLlmClient().buildCompletionRequest(
            LlmBackend.of(Provider.OLLAMA, null), conversation());
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getPath().getValue(), is("/api/chat"));
        String body = request.getBodyAsString();
        assertThat(body, containsString("\"stream\":false"));
        assertThat(body, containsString("\"temperature\":0"));
        assertThat(body, containsString("\"seed\":0"));
        assertThat(body, containsString("capital of France"));
    }

    @Test
    public void ollamaParsesChatResponse() {
        Completion completion = new OllamaLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"message\":{\"role\":\"assistant\",\"content\":\"Paris.\"}," +
                "\"done_reason\":\"stop\",\"prompt_eval_count\":11,\"eval_count\":3}"));
        assertThat(completion.getText(), is("Paris."));
        assertThat(completion.getStopReason(), is("stop"));
        assertThat(completion.getUsage().getInputTokens(), is(11));
        assertThat(completion.getUsage().getOutputTokens(), is(3));
    }

    @Test
    public void ollamaHonoursBackendBaseUrl() {
        HttpRequest request = new OllamaLlmClient().buildCompletionRequest(
            new LlmBackend(null, Provider.OLLAMA, "http://ollama.internal:9999", null, "mistral", null, null),
            conversation());
        assertThat(request.getFirstHeader("Host"), is("ollama.internal:9999"));
        assertThat(request.getBodyAsString(), containsString("\"model\":\"mistral\""));
    }

    // --- OpenAI ---

    @Test
    public void openAiBuildsBearerAuthAndChatBody() {
        HttpRequest request = new OpenAiLlmClient().buildCompletionRequest(
            LlmBackend.of(Provider.OPENAI, "sk-test"), conversation());
        assertThat(request.getPath().getValue(), is("/v1/chat/completions"));
        assertThat(request.getFirstHeader("Authorization"), is("Bearer sk-test"));
        assertThat(request.getFirstHeader("Host"), is("api.openai.com"));
    }

    @Test
    public void openAiParsesChatResponse() {
        Completion completion = new OpenAiLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"choices\":[{\"message\":{\"content\":\"Paris\"},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":1}}"));
        assertThat(completion.getText(), is("Paris"));
        assertThat(completion.getStopReason(), is("stop"));
        assertThat(completion.getUsage().getInputTokens(), is(9));
    }

    // --- Anthropic ---

    @Test
    public void anthropicHoistsSystemAndSetsVersionAndKey() {
        HttpRequest request = new AnthropicLlmClient().buildCompletionRequest(
            LlmBackend.of(Provider.ANTHROPIC, "ak-test"), conversation());
        assertThat(request.getPath().getValue(), is("/v1/messages"));
        assertThat(request.getFirstHeader("x-api-key"), is("ak-test"));
        assertThat(request.getFirstHeader("anthropic-version"), is(notNullValue()));
        String body = request.getBodyAsString();
        assertThat(body, containsString("\"system\":\"You are helpful.\""));
        // system message must NOT appear in the messages array
        assertThat(body, containsString("\"role\":\"user\""));
    }

    @Test
    public void anthropicParsesContentBlocks() {
        Completion completion = new AnthropicLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"content\":[{\"type\":\"text\",\"text\":\"Paris\"}],\"stop_reason\":\"end_turn\"," +
                "\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}"));
        assertThat(completion.getText(), is("Paris"));
        assertThat(completion.getStopReason(), is("end_turn"));
        assertThat(completion.getUsage().getOutputTokens(), is(2));
    }

    // --- Gemini ---

    @Test
    public void geminiPutsKeyInQueryAndModelInPath() {
        HttpRequest request = new GeminiLlmClient().buildCompletionRequest(
            new LlmBackend(null, Provider.GEMINI, null, "gk-test", "gemini-1.5-pro", null, null),
            conversation());
        assertThat(request.getPath().getValue(), containsString("/v1beta/models/gemini-1.5-pro:generateContent"));
        assertThat(request.getFirstQueryStringParameter("key"), is("gk-test"));
    }

    @Test
    public void geminiParsesCandidates() {
        Completion completion = new GeminiLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Paris\"}]},\"finishReason\":\"STOP\"}]," +
                "\"usageMetadata\":{\"promptTokenCount\":8,\"candidatesTokenCount\":1}}"));
        assertThat(completion.getText(), is("Paris"));
        assertThat(completion.getStopReason(), is("STOP"));
        assertThat(completion.getUsage().getInputTokens(), is(8));
    }

    // --- OpenAI Responses ---

    @Test
    public void openAiResponsesBuildsBearerAuthAndInputBody() {
        HttpRequest request = new OpenAiResponsesLlmClient().buildCompletionRequest(
            LlmBackend.of(Provider.OPENAI_RESPONSES, "sk-resp"), conversation());
        assertThat(request.getPath().getValue(), is("/v1/responses"));
        assertThat(request.getFirstHeader("Authorization"), is("Bearer sk-resp"));
        assertThat(request.getBodyAsString(), containsString("\"input\""));
    }

    @Test
    public void openAiResponsesParsesOutputText() {
        Completion completion = new OpenAiResponsesLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"output_text\":\"Paris\",\"status\":\"completed\"," +
                "\"usage\":{\"input_tokens\":11,\"output_tokens\":2}}"));
        assertThat(completion.getText(), is("Paris"));
        assertThat(completion.getStopReason(), is("completed"));
        assertThat(completion.getUsage().getInputTokens(), is(11));
    }

    // --- Azure OpenAI ---

    @Test
    public void azureOpenAiUsesDeploymentPathAndApiKeyHeader() {
        HttpRequest request = new AzureOpenAiLlmClient().buildCompletionRequest(
            LlmBackend.of(Provider.AZURE_OPENAI, "az-key"), conversation());
        assertThat(request.getPath().getValue(), is("/openai/deployments/gpt-4o-mini/chat/completions"));
        assertThat(request.getFirstHeader("api-key"), is("az-key"));
        // Azure uses the api-key header, NOT Authorization: Bearer
        assertThat(request.getFirstHeader("Authorization"), is(""));
        assertThat(request.getFirstQueryStringParameter("api-version"), is(notNullValue()));
    }

    @Test
    public void azureOpenAiParsesChatResponseLikeOpenAi() {
        Completion completion = new AzureOpenAiLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"choices\":[{\"message\":{\"content\":\"Paris\"},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":1}}"));
        assertThat(completion.getText(), is("Paris"));
        assertThat(completion.getUsage().getInputTokens(), is(7));
    }

    // --- Bedrock (Anthropic-on-Bedrock) ---

    @Test
    public void bedrockUsesInvokePathWithAnthropicBody() {
        HttpRequest request = new BedrockLlmClient().buildCompletionRequest(
            LlmBackend.of(Provider.BEDROCK, null), conversation());
        assertThat(request.getPath().getValue(), containsString("/model/"));
        assertThat(request.getPath().getValue(), containsString("/invoke"));
        // Anthropic-shaped body: system hoisted out, a messages array remains
        String body = request.getBodyAsString();
        assertThat(body, containsString("\"messages\""));
        assertThat(body, containsString("\"system\":\"You are helpful.\""));
    }

    @Test
    public void bedrockParsesAnthropicShapedResponse() {
        Completion completion = new BedrockLlmClient().parseCompletionResponse(response()
            .withStatusCode(200)
            .withBody("{\"content\":[{\"type\":\"text\",\"text\":\"Paris\"}],\"stop_reason\":\"end_turn\"," +
                "\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}"));
        assertThat(completion.getText(), is("Paris"));
        assertThat(completion.getUsage().getOutputTokens(), is(3));
    }

    // --- registry ---

    @Test
    public void registryHasAllSevenProviders() {
        for (Provider provider : Provider.values()) {
            assertThat("client for " + provider, LlmClientRegistry.getInstance().lookup(provider).isPresent(), is(true));
        }
    }

    @Test
    public void backendRedactsApiKeyInToString() {
        LlmBackend backend = new LlmBackend("prod", Provider.OPENAI, null, "sk-secret-value", null,
            Collections.emptyMap(), null);
        assertThat(backend.toString(), containsString("***"));
        assertThat(backend.toString().contains("sk-secret-value"), is(false));
    }
}
