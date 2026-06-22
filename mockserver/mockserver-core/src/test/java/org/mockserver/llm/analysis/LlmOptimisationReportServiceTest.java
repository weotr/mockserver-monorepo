package org.mockserver.llm.analysis;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LlmOptimisationReportServiceTest {

    private final LlmOptimisationReportService service = new LlmOptimisationReportService();

    @After
    public void resetConfig() {
        ConfigurationProperties.llmOptimisationMaxCalls(200);
        ConfigurationProperties.fixtureBodyRedactFields("");
    }

    private static LogEventRequestAndResponse openAiPair(String host, String model, int in, int out) {
        HttpRequest req = request().withMethod("POST").withPath("/v1/chat/completions")
            .withHeader("Host", host)
            .withHeader("Authorization", "Bearer sk-secret-abc")
            .withBody("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},{\"role\":\"user\",\"content\":\"hi\"}]}");
        HttpResponse resp = response().withStatusCode(200)
            .withBody("{\"model\":\"" + model + "\",\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + "}}");
        return new LogEventRequestAndResponse().withHttpRequest(req).withHttpResponse(resp);
    }

    private static LlmOptimisationReportService.Filter noFilter() {
        return new LlmOptimisationReportService.Filter(null, null, null);
    }

    @Test
    public void buildsReportFromPairs() {
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(
            openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10),
            openAiPair("api.openai.com", "gpt-4o-2024-08-06", 200, 20));
        LlmOptimisationReportService.Result result = service.build(pairs, noFilter());

        LlmOptimisationReport report = result.getReport();
        assertEquals(2, report.getTotals().getCallCount());
        assertEquals(300, report.getTotals().getInputTokens());
        assertEquals("host:api.openai.com", report.getSession().getKey());
        assertTrue(report.getRedaction().getRedactedHeaders().stream().anyMatch(h -> h.equalsIgnoreCase("authorization")));
    }

    @Test
    public void excludesResponseLessPreLogGhostPairs() {
        // The LLM dispatch path writes an informational request-only EXPECTATION_RESPONSE
        // (no httpResponse) alongside the real one. The report must count the call ONCE,
        // not double-count, and must not falsely trip DUPLICATE_CONSECUTIVE_CALL.
        LogEventRequestAndResponse real = openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10);
        LogEventRequestAndResponse ghost = new LogEventRequestAndResponse()
            .withHttpRequest(real.getHttpRequest()); // same request, NO response
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(ghost, real);

        LlmOptimisationReportService.Result result = service.build(pairs, noFilter());
        LlmOptimisationReport report = result.getReport();
        assertEquals(1, report.getTotals().getCallCount());
        assertTrue(report.getSignals().stream().noneMatch(s -> "DUPLICATE_CONSECUTIVE_CALL".equals(s.getId())));
    }

    @Test
    public void includesMockedLocalhostLlmTraffic() {
        // Regression: MOCKED LLM traffic (served by MockServer on localhost, as in
        // `npm run demo`) has no provider host, so host-based sniff() misses it. The
        // report must still include it via path-based detection — matching the
        // Sessions view, which is what the user sees populated.
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(
            openAiPair("localhost:1080", "gpt-4o-2024-08-06", 100, 10));
        LlmOptimisationReportService.Result result = service.build(pairs, noFilter());

        LlmOptimisationReport report = result.getReport();
        assertEquals(1, report.getTotals().getCallCount());
        assertEquals("OPENAI", report.getCalls().get(0).getProvider());
        assertEquals("host:localhost", report.getSession().getKey());
    }

    @Test
    public void emptyCaptureYieldsEmptyReportAndBrief() {
        LlmOptimisationReportService.Result result = service.build(Collections.emptyList(), noFilter());
        assertThat(result.getReport().getCalls(), is(empty()));
        String brief = service.renderBrief(result);
        assertThat(brief, containsString("No LLM traffic captured"));
    }

    @Test
    public void nullPairsHandledGracefully() {
        LlmOptimisationReportService.Result result = service.build(null, noFilter());
        assertThat(result.getReport().getCalls(), is(empty()));
    }

    @Test
    public void nullEntriesAndNullRequestsAreSkipped() {
        List<LogEventRequestAndResponse> pairs = new ArrayList<>();
        pairs.add(null);
        pairs.add(new LogEventRequestAndResponse().withHttpResponse(response().withBody("{}"))); // null request
        pairs.add(openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10));
        LlmOptimisationReportService.Result result = service.build(pairs, noFilter());
        assertEquals(1, result.getReport().getCalls().size());
    }

    @Test
    public void nonLlmTrafficIsExcluded() {
        LogEventRequestAndResponse notLlm = new LogEventRequestAndResponse()
            .withHttpRequest(request().withMethod("GET").withPath("/api/users").withHeader("Host", "example.com"))
            .withHttpResponse(response().withBody("[]"));
        LlmOptimisationReportService.Result result = service.build(
            Collections.singletonList(notLlm), noFilter());
        assertThat(result.getReport().getCalls(), is(empty()));
    }

    @Test
    public void hostFilterRestrictsTraffic() {
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(
            openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10),
            new LogEventRequestAndResponse()
                .withHttpRequest(request().withMethod("POST").withPath("/v1/messages").withHeader("Host", "api.anthropic.com")
                    .withBody("{\"model\":\"claude-sonnet-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .withHttpResponse(response().withStatusCode(200)
                    .withBody("{\"model\":\"claude-sonnet-4\",\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":50,\"output_tokens\":5}}")));

        LlmOptimisationReportService.Result result = service.build(pairs,
            new LlmOptimisationReportService.Filter(null, "api.openai.com", null));
        assertEquals(1, result.getReport().getCalls().size());
        assertEquals("OPENAI", result.getReport().getCalls().get(0).getProvider());
    }

    @Test
    public void providerFilterRestrictsTraffic() {
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(
            openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10));
        LlmOptimisationReportService.Result anthropicOnly = service.build(pairs,
            new LlmOptimisationReportService.Filter(null, null, "ANTHROPIC"));
        assertThat(anthropicOnly.getReport().getCalls(), is(empty()));

        LlmOptimisationReportService.Result openaiOnly = service.build(pairs,
            new LlmOptimisationReportService.Filter(null, null, "OPENAI"));
        assertEquals(1, openaiOnly.getReport().getCalls().size());
    }

    @Test
    public void sessionFilterMatchesGroupingKey() {
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(
            openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10));
        LlmOptimisationReportService.Result match = service.build(pairs,
            new LlmOptimisationReportService.Filter("host:api.openai.com", null, null));
        assertEquals(1, match.getReport().getCalls().size());

        LlmOptimisationReportService.Result noMatch = service.build(pairs,
            new LlmOptimisationReportService.Filter("host:other.host", null, null));
        assertThat(noMatch.getReport().getCalls(), is(empty()));
    }

    @Test
    public void sessionFilterMatchesBareHostFromDashboardPicker() {
        // The dashboard session picker sends the bare host (e.g. "api.openai.com"),
        // not the composite "host:<host>" grouping key — the filter accepts both.
        List<LogEventRequestAndResponse> pairs = java.util.Arrays.asList(
            openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10));
        LlmOptimisationReportService.Result match = service.build(pairs,
            new LlmOptimisationReportService.Filter("api.openai.com", null, null));
        assertEquals(1, match.getReport().getCalls().size());

        // The picker derives its value from the raw Host header, which may carry a
        // port; the server strips the port from both sides before comparing.
        LlmOptimisationReportService.Result withPort = service.build(pairs,
            new LlmOptimisationReportService.Filter("api.openai.com:443", null, null));
        assertEquals(1, withPort.getReport().getCalls().size());

        // A value that matches neither the composite key nor the host (e.g. an
        // isolation key the host-grouped v1 server can't resolve) returns an
        // empty report gracefully rather than throwing.
        LlmOptimisationReportService.Result noMatch = service.build(pairs,
            new LlmOptimisationReportService.Filter("agent-7f3a", null, null));
        assertThat(noMatch.getReport().getCalls(), is(empty()));
    }

    @Test
    public void maxCallsBoundsReportToMostRecent() {
        ConfigurationProperties.llmOptimisationMaxCalls(2);
        List<LogEventRequestAndResponse> pairs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pairs.add(openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100 + i, 10));
        }
        LlmOptimisationReportService.Result result = service.build(pairs, noFilter());
        assertEquals(2, result.getReport().getCalls().size());
        // most recent kept: last two had input 103 and 104
        assertEquals(103, result.getReport().getCalls().get(0).getInputTokens());
        assertEquals(104, result.getReport().getCalls().get(1).getInputTokens());
    }

    @Test
    public void jsonReportSchemaVersionAndGeneratedBy() {
        LlmOptimisationReportService.Result result = service.build(
            Collections.singletonList(openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10)), noFilter());
        assertEquals(LlmOptimisationReport.SCHEMA_VERSION, result.getReport().getSchemaVersion());
        assertEquals("mockserver", result.getReport().getGeneratedBy());
    }

    @Test
    public void perCallLatencyReadFromResponseTimeHeader() {
        // The forwarded response carries the upstream round-trip time on the internal
        // x-mockserver-response-time-ms header (logged-clone only). The report reads it.
        LogEventRequestAndResponse pair = openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10);
        pair.getHttpResponse().withHeader("x-mockserver-response-time-ms", "1234");

        LlmOptimisationReportService.Result result = service.build(Collections.singletonList(pair), noFilter());
        assertEquals(1, result.getReport().getCalls().size());
        assertEquals(1234L, result.getReport().getCalls().get(0).getLatencyMs());
        assertEquals(1234L, result.getReport().getTotals().getTotalLatencyMs());
    }

    @Test
    public void malformedLatencyHeaderYieldsZeroLatencyGracefully() {
        LogEventRequestAndResponse pair = openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10);
        pair.getHttpResponse().withHeader("x-mockserver-response-time-ms", "not-a-number");

        LlmOptimisationReportService.Result result = service.build(Collections.singletonList(pair), noFilter());
        assertEquals(1, result.getReport().getCalls().size());
        assertEquals(0L, result.getReport().getCalls().get(0).getLatencyMs());
        assertEquals(0L, result.getReport().getTotals().getTotalLatencyMs());
    }

    @Test
    public void absentLatencyHeaderYieldsZeroLatency() {
        LogEventRequestAndResponse pair = openAiPair("api.openai.com", "gpt-4o-2024-08-06", 100, 10);
        LlmOptimisationReportService.Result result = service.build(Collections.singletonList(pair), noFilter());
        assertEquals(0L, result.getReport().getCalls().get(0).getLatencyMs());
    }

    @Test
    public void briefRedactsConfiguredBodyFields() {
        ConfigurationProperties.fixtureBodyRedactFields("content");
        HttpRequest req = request().withMethod("POST").withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"messages\":[{\"role\":\"user\",\"content\":\"super secret prompt\"}]}");
        HttpResponse resp = response().withStatusCode(200)
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}");
        LogEventRequestAndResponse pair = new LogEventRequestAndResponse().withHttpRequest(req).withHttpResponse(resp);

        LlmOptimisationReportService.Result result = service.build(Collections.singletonList(pair), noFilter());
        String brief = service.renderBrief(result);
        assertThat(brief, org.hamcrest.Matchers.not(containsString("super secret prompt")));
        assertTrue(result.getReport().getRedaction().getRedactedBodyFields().contains("content"));
    }
}
