package org.mockserver.llm.analysis;

import org.junit.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class LlmOptimisationCsvRendererTest {

    private final LlmOptimisationCsvRenderer renderer = new LlmOptimisationCsvRenderer();

    private static final String CALLS_HEADER =
        "index,provider,model,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,"
            + "estimated_cost_usd,cost_is_estimated,latency_ms,tool_calls,finish_reason";

    private static LlmOptimisationReport.Call call(int index, String provider, String model,
                                                   long in, long out, double cost, long latency,
                                                   int toolCalls, String finish) {
        LlmOptimisationReport.Call call = new LlmOptimisationReport.Call()
            .setIndex(index)
            .setProvider(provider)
            .setModel(model)
            .setInputTokens(in)
            .setOutputTokens(out)
            .setEstimatedCostUsd(cost)
            .setLatencyMs(latency)
            .setFinishReason(finish);
        for (int i = 0; i < toolCalls; i++) {
            call.getToolCalls().add(new LlmOptimisationReport.ToolCall().setName("tool" + i));
        }
        return call;
    }

    private LlmOptimisationReport reportWithThreeCalls() {
        LlmOptimisationReport report = new LlmOptimisationReport();
        report.setCalls(Arrays.asList(
            call(0, "OPENAI", "gpt-4o", 8120, 540, 0.0235, 2300, 1, "tool_calls"),
            call(1, "OPENAI", "gpt-4o", 8200, 480, 0.0240, 1900, 0, "stop"),
            call(2, "ANTHROPIC", "claude-opus-4", 12000, 800, 0.1800, 3100, 2, "end_turn")));
        report.getTotals()
            .setCallCount(3)
            .setInputTokens(28320)
            .setOutputTokens(1820)
            .setCachedInputTokens(4000)
            .setEstimatedCostUsd(0.2275)
            .setTotalLatencyMs(7300)
            .setToolCallCount(3)
            .setCacheHitRatio(0.5)
            .setOneShotRate(0.6667)
            .setRetryCallCount(1);
        report.getVerdict()
            .setGrade("C")
            .setTotalEstimatedSavingUsd(0.0410)
            .setTotalWastedInputTokens(8120)
            .setSavingFractionOfSpend(0.18)
            .setHighCount(1)
            .setMediumCount(2)
            .setLowCount(0);
        report.getSignals().add(new LlmOptimisationReport.Signal().setId("LOW_CACHE_HIT_RATE"));
        return report;
    }

    private static String[] lines(String csv) {
        return csv.split("\n", -1);
    }

    @Test
    public void rendersPerCallHeaderAndOneRowPerCall() {
        String csv = renderer.render(reportWithThreeCalls());
        String[] lines = lines(csv);

        assertThat("header is first line", lines[0], equalTo(CALLS_HEADER));
        assertThat("first call row", lines[1],
            equalTo("0,OPENAI,gpt-4o,8120,540,0,0,0.023500,false,2300,1,tool_calls"));
        assertThat("second call row", lines[2],
            equalTo("1,OPENAI,gpt-4o,8200,480,0,0,0.024000,false,1900,0,stop"));
        assertThat("third call row", lines[3],
            equalTo("2,ANTHROPIC,claude-opus-4,12000,800,0,0,0.180000,false,3100,2,end_turn"));
    }

    @Test
    public void emitsTotalsAndVerdictSummarySection() {
        String csv = renderer.render(reportWithThreeCalls());

        assertThat(csv, containsString("\nsection,metric,value\n"));
        assertThat(csv, containsString("totals,call_count,3"));
        assertThat(csv, containsString("totals,input_tokens,28320"));
        assertThat(csv, containsString("totals,cached_input_tokens,4000"));
        assertThat(csv, containsString("totals,estimated_cost_usd,0.227500"));
        assertThat(csv, containsString("totals,cache_hit_ratio,0.5000"));
        assertThat(csv, containsString("totals,one_shot_rate,0.6667"));
        assertThat(csv, containsString("totals,retry_call_count,1"));
        assertThat(csv, containsString("verdict,grade,C"));
        assertThat(csv, containsString("verdict,total_estimated_saving_usd,0.041000"));
        assertThat(csv, containsString("verdict,total_wasted_input_tokens,8120"));
        assertThat(csv, containsString("verdict,saving_fraction_of_spend,0.1800"));
        assertThat(csv, containsString("verdict,high_count,1"));
        assertThat(csv, containsString("verdict,medium_count,2"));
    }

    @Test
    public void escapesFieldsContainingCommaQuoteAndNewline() {
        LlmOptimisationReport report = new LlmOptimisationReport();
        report.setCalls(Arrays.asList(
            // model carrying a comma must be quoted; finish reason carrying a quote must have it doubled.
            call(0, "OPENAI", "gpt-4o,preview", 10, 5, 0.01, 100, 0, "stop\"now"),
            // finish reason with an embedded newline must be quoted.
            call(1, "OPENAI", "gpt-4o", 10, 5, 0.01, 100, 0, "line1\nline2")));
        report.getTotals().setCallCount(2);

        String csv = renderer.render(report);

        assertThat("comma field quoted", csv, containsString(",\"gpt-4o,preview\","));
        assertThat("embedded quote doubled and field quoted", csv, containsString(",\"stop\"\"now\""));
        assertThat("embedded newline keeps field quoted", csv, containsString(",\"line1\nline2\""));
    }

    @Test
    public void emptyReportRendersHeaderOnlyCallsSectionPlusZeroTotals() {
        String csv = renderer.render(new LlmOptimisationReport());
        String[] lines = lines(csv);

        // line 0: header; line 1: blank separator; line 2: summary header; then summary rows.
        assertThat("header present", lines[0], equalTo(CALLS_HEADER));
        assertThat("no data rows before the blank separator", lines[1], is(""));
        assertThat("summary header follows", lines[2], equalTo("section,metric,value"));
        assertThat(csv, containsString("totals,call_count,0"));
        assertThat(csv, containsString("verdict,grade,A"));
        assertThat("no data row leaked", csv, not(containsString("OPENAI")));
    }

    @Test
    public void nullReportRendersValidHeaderOnlyCsv() {
        String csv = renderer.render(null);
        assertThat(lines(csv)[0], equalTo(CALLS_HEADER));
        assertThat(csv, containsString("totals,call_count,0"));
    }

    @Test
    public void escapeHelperHandlesNullAndPlainValues() {
        assertThat(LlmOptimisationCsvRenderer.escape(null), equalTo(""));
        assertThat(LlmOptimisationCsvRenderer.escape("plain"), equalTo("plain"));
        assertThat(LlmOptimisationCsvRenderer.escape("a,b"), equalTo("\"a,b\""));
        assertThat(LlmOptimisationCsvRenderer.escape("a\"b"), equalTo("\"a\"\"b\""));
    }

    @Test
    public void usesDotDecimalSeparatorRegardlessOfDefaultLocale() {
        // production Docker images / JARs inherit the host locale (unlike the en-GB test argLine);
        // a comma-decimal locale must NOT corrupt the CSV by shifting unescaped numeric columns.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY); // comma decimal separator
            String csv = renderer.render(reportWithThreeCalls());
            assertThat("cost uses dot, stays a single unescaped column", csv,
                containsString("0,OPENAI,gpt-4o,8120,540,0,0,0.023500,false,2300,1,tool_calls"));
            assertThat("totals cost uses dot", csv, containsString("totals,estimated_cost_usd,0.227500"));
            assertThat("ratio uses dot", csv, containsString("totals,cache_hit_ratio,0.5000"));
            assertThat("no comma decimal leaked", csv, not(containsString("0,023500")));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
