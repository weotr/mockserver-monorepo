package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.Provider;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pipeline-safe (CI-safe, no secrets, generic hosts) end-to-end check that traffic from
 * the three coding CLIs — claude (Anthropic), tabnine (OpenAI Chat Completions) and
 * opencode (OpenAI Responses via the Codex backend) — is recognised as LLM traffic and
 * included in the optimisation report.
 *
 * <p>tabnine deliberately targets a non-well-known host ({@code llm.example.test}) to
 * prove PATH-based detection; opencode targets {@code chatgpt.com/backend-api/codex/responses}
 * to prove the Codex backend Responses path is recognised.
 */
public class CodingCliLlmCaptureTest {

    private final LlmOptimisationReportService service = new LlmOptimisationReportService();

    private static LlmOptimisationReportService.Filter noFilter() {
        return new LlmOptimisationReportService.Filter(null, null, null);
    }

    // (a) claude / Anthropic — POST /v1/messages, Host api.anthropic.com
    private static LogEventRequestAndResponse claudeAnthropicPair() {
        HttpRequest req = request().withMethod("POST").withPath("/v1/messages")
            .withHeader("Host", "api.anthropic.com")
            .withHeader("Content-Type", "application/json")
            .withBody("{\"model\":\"claude-opus-4-8\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly the single word: hello\"}]}");
        HttpResponse resp = response().withStatusCode(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-4-8\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":15,\"output_tokens\":1}}");
        return new LogEventRequestAndResponse().withHttpRequest(req).withHttpResponse(resp);
    }

    // (b) tabnine / OpenAI Chat Completions — POST /chat/openai/v1/chat/completions,
    // Host llm.example.test (NOT a well-known host → proves path-based detection)
    private static LogEventRequestAndResponse tabnineOpenAiPair() {
        HttpRequest req = request().withMethod("POST").withPath("/chat/openai/v1/chat/completions")
            .withHeader("Host", "llm.example.test")
            .withHeader("Content-Type", "application/json")
            .withBody("{\"model\":\"gpt-5.4\",\"stream\":true,\"messages\":[{\"role\":\"system\",\"content\":\"You are a CLI agent.\"},{\"role\":\"user\",\"content\":\"Reply with exactly the single word: hello\"}]}");
        String sse =
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"index\":0,\"finish_reason\":null}],\"created\":1782463458,\"id\":\"chatcmpl-1\",\"model\":\"gpt-5.4\",\"object\":\"chat.completion.chunk\"}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"index\":0,\"finish_reason\":null}],\"created\":1782463458,\"id\":\"chatcmpl-1\",\"model\":\"gpt-5.4\",\"object\":\"chat.completion.chunk\"}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}],\"created\":1782463458,\"id\":\"chatcmpl-1\",\"model\":\"gpt-5.4\",\"object\":\"chat.completion.chunk\"}\n\n"
                + "data: [DONE]\n\n";
        HttpResponse resp = response().withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withBody(sse);
        return new LogEventRequestAndResponse().withHttpRequest(req).withHttpResponse(resp);
    }

    // (c) opencode / OpenAI Responses (Codex) — POST /backend-api/codex/responses, Host chatgpt.com
    private static LogEventRequestAndResponse opencodeResponsesPair() {
        HttpRequest req = request().withMethod("POST").withPath("/backend-api/codex/responses")
            .withHeader("Host", "chatgpt.com")
            .withHeader("Content-Type", "application/json")
            .withBody("{\"model\":\"gpt-5.5\",\"stream\":true,\"instructions\":\"You are a helpful assistant.\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Reply with exactly the single word: hello\"}]}]}");
        String sse =
            "event: response.created\n"
                + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\",\"model\":\"gpt-5.5\"}}\n\n"
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"content_index\":0,\"delta\":\"hello\"}\n\n"
                + "event: response.output_text.done\n"
                + "data: {\"type\":\"response.output_text.done\",\"content_index\":0,\"text\":\"hello\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"model\":\"gpt-5.5\",\"usage\":{\"input_tokens\":825,\"output_tokens\":6,\"total_tokens\":831}}}\n\n";
        HttpResponse resp = response().withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withBody(sse);
        return new LogEventRequestAndResponse().withHttpRequest(req).withHttpResponse(resp);
    }

    @Test
    public void detectsProviderForEachCodingCli() {
        assertThat(LlmProviderSniffer.detectForAnalysis(claudeAnthropicPair().getHttpRequest()),
            is(Optional.of(Provider.ANTHROPIC)));
        assertThat(LlmProviderSniffer.detectForAnalysis(tabnineOpenAiPair().getHttpRequest()),
            is(Optional.of(Provider.OPENAI)));
        assertThat(LlmProviderSniffer.detectForAnalysis(opencodeResponsesPair().getHttpRequest()),
            is(Optional.of(Provider.OPENAI_RESPONSES)));
    }

    @Test
    public void includesAllThreeCodingCliExchangesInOptimisationReport() {
        List<LogEventRequestAndResponse> pairs = Arrays.asList(
            claudeAnthropicPair(),
            tabnineOpenAiPair(),
            opencodeResponsesPair());

        LlmOptimisationReportService.Result result = service.build(pairs, noFilter());

        // All three coding-CLI exchanges are recognised as LLM traffic and included.
        assertEquals(3, result.getIncludedExchanges().size());
        assertEquals(3, result.getReport().getCalls().size());
        assertEquals(3, result.getReport().getTotals().getCallCount());
    }
}
