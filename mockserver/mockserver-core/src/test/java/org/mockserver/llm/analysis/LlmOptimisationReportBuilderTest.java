package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LlmOptimisationReportBuilderTest {

    private final LlmOptimisationReportBuilder builder = new LlmOptimisationReportBuilder();

    private static HttpRequest openAiRequest(String model, String systemPrompt, String userText) {
        return request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withBody("{\"model\":\"" + model + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + userText + "\"}]}");
    }

    private static HttpResponse openAiUsageResponse(String model, int inputTokens, int outputTokens, String finishReason) {
        return response()
            .withStatusCode(200)
            .withBody("{\"model\":\"" + model + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":" + inputTokens + ",\"completion_tokens\":" + outputTokens + "}}");
    }

    private static CapturedExchange exchange(HttpRequest req, HttpResponse resp, Long latency) {
        return new CapturedExchange(req, resp, latency);
    }

    private static List<String> headers() {
        return Arrays.asList("authorization", "x-api-key");
    }

    // --- empty / non-LLM ---

    @Test
    public void emptyExchangesYieldEmptyReport() {
        LlmOptimisationReport report = builder.build(Collections.emptyList(), "host:none",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());
        assertThat(report.getCalls(), is(empty()));
        assertThat(report.getSignals(), is(empty()));
        assertEquals(0, report.getTotals().getCallCount());
        assertEquals(LlmOptimisationReport.SCHEMA_VERSION, report.getSchemaVersion());
        assertEquals("mockserver", report.getGeneratedBy());
    }

    @Test
    public void nonLlmTrafficIsIgnored() {
        HttpRequest notLlm = request().withMethod("GET").withPath("/api/users").withHeader("Host", "example.com");
        LlmOptimisationReport report = builder.build(
            Collections.singletonList(exchange(notLlm, response().withBody("[]"), 10L)),
            "host:example.com", LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());
        assertThat(report.getCalls(), is(empty()));
    }

    // --- session / grouping basis ---

    @Test
    public void sessionCarriesGroupingBasisProvidersAndModels() {
        LlmOptimisationReport report = builder.build(
            Collections.singletonList(exchange(
                openAiRequest("gpt-4o-2024-08-06", "you are helpful", "hello"),
                openAiUsageResponse("gpt-4o-2024-08-06", 100, 20, "stop"), 1200L)),
            "host:api.openai.com", LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());

        assertEquals("host:api.openai.com", report.getSession().getKey());
        assertEquals(LlmOptimisationReport.GroupingBasis.PROXY_HOST, report.getSession().getGroupingBasis());
        assertThat(report.getSession().getProviders(), contains("OPENAI"));
        assertThat(report.getSession().getModels(), contains("gpt-4o-2024-08-06"));
    }

    @Test
    public void groupingBasisDefaultsToProxyHostWhenNull() {
        LlmOptimisationReport report = builder.build(Collections.emptyList(), "k", null, headers(), null);
        assertEquals(LlmOptimisationReport.GroupingBasis.PROXY_HOST, report.getSession().getGroupingBasis());
    }

    // --- cost math: real usage (costIsEstimated false) ---

    @Test
    public void realUsageGivesExactTokensAndCostNotEstimated() {
        // gpt-4o pricing: 2.5 in / 10.0 out per million. 8120 in, 540 out.
        LlmOptimisationReport report = builder.build(
            Collections.singletonList(exchange(
                openAiRequest("gpt-4o-2024-08-06", "sys", "hi"),
                openAiUsageResponse("gpt-4o-2024-08-06", 8120, 540, "tool_calls"), 2300L)),
            "host:api.openai.com", LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());

        LlmOptimisationReport.Call call = report.getCalls().get(0);
        assertEquals(8120, call.getInputTokens());
        assertEquals(540, call.getOutputTokens());
        assertFalse(call.isCostIsEstimated());
        assertEquals("tool_calls", call.getFinishReason());
        assertEquals(2300, call.getLatencyMs());
        double expected = (8120 / 1_000_000.0) * 2.5 + (540 / 1_000_000.0) * 10.0;
        assertEquals(LlmOptimisationReportBuilder.round4(expected), call.getEstimatedCostUsd(), 0.00001);
        assertFalse(report.getTotals().isCostIsEstimated());
        assertEquals(call.getEstimatedCostUsd(), report.getTotals().getEstimatedCostUsd(), 0.00001);
    }

    // --- cost math: estimated (no usage in response) ---

    @Test
    public void absentUsageEstimatesTokensAndFlagsEstimated() {
        HttpResponse noUsage = response().withStatusCode(200)
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"a longer answer here\"},\"finish_reason\":\"stop\"}]}");
        LlmOptimisationReport report = builder.build(
            Collections.singletonList(exchange(
                openAiRequest("gpt-4o-2024-08-06", "system context that is moderately long", "user question text"),
                noUsage, 900L)),
            "host:api.openai.com", LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());

        LlmOptimisationReport.Call call = report.getCalls().get(0);
        assertTrue(call.isCostIsEstimated());
        assertThat(call.getInputTokens(), greaterThan(0L));
        assertTrue(report.getTotals().isCostIsEstimated());
    }

    @Test
    public void unknownModelYieldsZeroCost() {
        LlmOptimisationReport report = builder.build(
            Collections.singletonList(exchange(
                openAiRequest("some-unpriced-model", "sys", "hi"),
                openAiUsageResponse("some-unpriced-model", 1000, 100, "stop"), 100L)),
            "host:api.openai.com", LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());
        assertEquals(0.0, report.getCalls().get(0).getEstimatedCostUsd(), 0.00001);
    }

    // --- fingerprinting ---

    @Test
    public void fingerprintIsEightHexCharsAndStable() {
        String fp1 = LlmOptimisationReportBuilder.fingerprint("hello world");
        String fp2 = LlmOptimisationReportBuilder.fingerprint("hello world");
        assertEquals(8, fp1.length());
        assertEquals(fp1, fp2);
        assertTrue(fp1.matches("[0-9a-f]{8}"));
        assertFalse(fp1.equals(LlmOptimisationReportBuilder.fingerprint("different")));
    }

    @Test
    public void shortFingerprintIsFourHexChars() {
        String fp = LlmOptimisationReportBuilder.shortFingerprint("{\"city\":\"Paris\"}");
        assertEquals(4, fp.length());
        assertTrue(fp.matches("[0-9a-f]{4}"));
    }

    @Test
    public void systemPromptFingerprintPopulatedFromRequest() {
        LlmOptimisationReport report = builder.build(
            Collections.singletonList(exchange(
                openAiRequest("gpt-4o-2024-08-06", "you are a helpful assistant", "hi"),
                openAiUsageResponse("gpt-4o-2024-08-06", 50, 10, "stop"), 100L)),
            "host:api.openai.com", LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());
        LlmOptimisationReport.Call call = report.getCalls().get(0);
        assertThat(call.getSystemPromptFingerprint(), notNullValue());
        assertThat(call.getSystemPromptTokens(), greaterThan(0L));
    }

    // --- redaction metadata ---

    @Test
    public void redactionMetadataIsCarriedThrough() {
        LlmOptimisationReport report = builder.build(Collections.emptyList(), "k",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST,
            Arrays.asList("authorization", "x-api-key"), Arrays.asList("apiKey"));
        assertTrue(report.getRedaction().isApplied());
        assertThat(report.getRedaction().getRedactedHeaders(), contains("authorization", "x-api-key"));
        assertThat(report.getRedaction().getRedactedBodyFields(), contains("apiKey"));
    }

    // --- totals aggregation ---

    @Test
    public void totalsAggregateAcrossCalls() {
        List<CapturedExchange> exchanges = new ArrayList<>();
        exchanges.add(exchange(openAiRequest("gpt-4o-2024-08-06", "sys", "a"),
            openAiUsageResponse("gpt-4o-2024-08-06", 100, 10, "stop"), 100L));
        exchanges.add(exchange(openAiRequest("gpt-4o-2024-08-06", "sys", "b"),
            openAiUsageResponse("gpt-4o-2024-08-06", 200, 20, "stop"), 200L));
        LlmOptimisationReport report = builder.build(exchanges, "host:api.openai.com",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST, headers(), Collections.emptyList());

        assertEquals(2, report.getTotals().getCallCount());
        assertEquals(300, report.getTotals().getInputTokens());
        assertEquals(30, report.getTotals().getOutputTokens());
        assertEquals(300, report.getTotals().getTotalLatencyMs());
    }
}
