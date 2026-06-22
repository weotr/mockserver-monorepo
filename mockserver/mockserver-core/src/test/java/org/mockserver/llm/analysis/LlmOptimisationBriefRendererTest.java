package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LlmOptimisationBriefRendererTest {

    private final LlmOptimisationBriefRenderer renderer = new LlmOptimisationBriefRenderer();
    private final LlmOptimisationReportBuilder builder = new LlmOptimisationReportBuilder();

    private static HttpRequest openAiRequest(String model, String systemPrompt, String userText) {
        return request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withHeader("Authorization", "Bearer sk-secret-key-1234567890")
            .withBody("{\"model\":\"" + model + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + userText + "\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"Get weather\"}}]}");
    }

    private static HttpResponse usageResponse(String model, int in, int out, String finish) {
        return response().withStatusCode(200)
            .withBody("{\"model\":\"" + model + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + "}}");
    }

    private List<CapturedExchange> sampleExchanges() {
        return java.util.Arrays.asList(
            new CapturedExchange(openAiRequest("gpt-4o-2024-08-06", "You are a helpful assistant with a long static brief.", "What is the weather in Paris?"),
                usageResponse("gpt-4o-2024-08-06", 8120, 540, "tool_calls"), 2300L),
            new CapturedExchange(openAiRequest("gpt-4o-2024-08-06", "You are a helpful assistant with a long static brief.", "And in London?"),
                usageResponse("gpt-4o-2024-08-06", 8200, 480, "stop"), 1900L));
    }

    private LlmOptimisationReport sampleReport(List<CapturedExchange> exchanges) {
        return builder.build(exchanges, "host:api.openai.com",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST,
            java.util.Arrays.asList("authorization", "x-api-key"), Collections.emptyList());
    }

    @Test
    public void emptyReportRendersNoTrafficBrief() {
        LlmOptimisationReport report = builder.build(Collections.emptyList(), "host:none",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST, Collections.emptyList(), Collections.emptyList());
        String md = renderer.render(report, Collections.emptyList(), new FixtureRedactor());
        assertThat(md, containsString(LlmOptimisationBriefRenderer.FRAMING_PREAMBLE));
        assertThat(md, containsString("No LLM traffic captured"));
    }

    @Test
    public void sectionsAppearInFrozenOrder() {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());

        int preamble = md.indexOf(LlmOptimisationBriefRenderer.FRAMING_PREAMBLE);
        int summary = md.indexOf("## Run summary");
        int table = md.indexOf("## Per-call breakdown");
        int opportunities = md.indexOf("## Detected opportunities");
        int appendix = md.indexOf("## Conversations & tool definitions (appendix)");

        assertThat(preamble, lessThan(summary));
        assertThat(summary, lessThan(table));
        assertThat(table, lessThan(opportunities));
        assertThat(opportunities, lessThan(appendix));
    }

    @Test
    public void briefNeverLeaksSecrets() {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        assertThat(md, not(containsString("sk-secret-key-1234567890")));
        assertThat(md, not(containsString("Bearer sk-")));
    }

    @Test
    public void briefIncludesPerCallTableHeaderAndToolDefinitions() {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        assertThat(md, containsString("| # | model | in tok | out tok | cost | latency | tools | finish |"));
        assertThat(md, containsString("get_weather"));
        assertThat(md, containsString("**Tool definitions:**"));
    }

    @Test
    public void matchesGoldenFile() throws IOException {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        String golden = readResource("/org/mockserver/llm/analysis/optimisation-brief.golden.md");
        assertEquals(normalise(golden), normalise(md));
    }

    private static String normalise(String s) {
        return s.replace("\r\n", "\n").trim();
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = LlmOptimisationBriefRendererTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing golden resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
