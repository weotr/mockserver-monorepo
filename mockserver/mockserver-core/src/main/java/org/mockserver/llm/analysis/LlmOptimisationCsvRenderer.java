package org.mockserver.llm.analysis;

import java.util.List;
import java.util.Locale;

/**
 * Renders an {@link LlmOptimisationReport} to CSV — a third export format
 * alongside the JSON bundle and the Markdown {@link LlmOptimisationBriefRenderer
 * brief}. The CSV is intended for spreadsheets and data pipelines: it is stable,
 * deterministic, and properly escaped (a field containing a comma, double-quote,
 * carriage return, or newline is wrapped in double quotes with embedded quotes
 * doubled, per RFC 4180).
 * <p>
 * The output has two sections separated by a blank line:
 * <ol>
 *   <li><b>Per-call rows</b> — one row per captured call (the headline per-call
 *       numbers: provider/model, token breakdown, cost, latency, tool calls,
 *       finish reason).</li>
 *   <li><b>Totals/summary</b> — a single {@code section,metric,value} table
 *       carrying the aggregate {@link LlmOptimisationReport.Totals} and the
 *       headline {@link LlmOptimisationReport.Verdict} figures.</li>
 * </ol>
 * An empty report renders the per-call header row (no data rows) followed by the
 * totals section with zeros, so the output is always a valid, header-bearing CSV.
 * <p>
 * Pure — no behaviour, no network, no LLM. The column set is documented in
 * {@code docs/code/llm-mocking.md}; it is additive to (not a substitute for) the
 * JSON bundle, so it is not a frozen wire contract.
 */
public class LlmOptimisationCsvRenderer {

    private static final String CALLS_HEADER =
        "index,provider,model,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,"
            + "estimated_cost_usd,cost_is_estimated,latency_ms,tool_calls,finish_reason";

    /**
     * Render the report to CSV. A null report renders an empty header-only CSV
     * (so callers never have to null-check the result).
     */
    public String render(LlmOptimisationReport report) {
        StringBuilder csv = new StringBuilder();

        LlmOptimisationReport.Totals totals = report != null && report.getTotals() != null
            ? report.getTotals() : new LlmOptimisationReport.Totals();
        LlmOptimisationReport.Verdict verdict = report != null && report.getVerdict() != null
            ? report.getVerdict() : new LlmOptimisationReport.Verdict();

        // Section 1: per-call rows.
        csv.append(CALLS_HEADER).append("\n");
        if (report != null && report.getCalls() != null) {
            for (LlmOptimisationReport.Call call : report.getCalls()) {
                appendCallRow(csv, call);
            }
        }

        // Section 2: totals / summary.
        csv.append("\n");
        csv.append("section,metric,value").append("\n");
        appendSummaryRow(csv, "totals", "call_count", String.valueOf(totals.getCallCount()));
        appendSummaryRow(csv, "totals", "input_tokens", String.valueOf(totals.getInputTokens()));
        appendSummaryRow(csv, "totals", "output_tokens", String.valueOf(totals.getOutputTokens()));
        appendSummaryRow(csv, "totals", "cached_input_tokens", String.valueOf(totals.getCachedInputTokens()));
        appendSummaryRow(csv, "totals", "reasoning_tokens", String.valueOf(totals.getReasoningTokens()));
        appendSummaryRow(csv, "totals", "estimated_cost_usd", formatUsd(totals.getEstimatedCostUsd()));
        appendSummaryRow(csv, "totals", "cost_is_estimated", String.valueOf(totals.isCostIsEstimated()));
        appendSummaryRow(csv, "totals", "total_latency_ms", String.valueOf(totals.getTotalLatencyMs()));
        appendSummaryRow(csv, "totals", "tool_call_count", String.valueOf(totals.getToolCallCount()));
        appendSummaryRow(csv, "totals", "cache_hit_ratio", formatRatio(totals.getCacheHitRatio()));
        appendSummaryRow(csv, "totals", "one_shot_rate", formatRatio(totals.getOneShotRate()));
        appendSummaryRow(csv, "totals", "retry_call_count", String.valueOf(totals.getRetryCallCount()));
        appendSummaryRow(csv, "verdict", "grade", verdict.getGrade());
        appendSummaryRow(csv, "verdict", "total_estimated_saving_usd", formatUsd(verdict.getTotalEstimatedSavingUsd()));
        appendSummaryRow(csv, "verdict", "total_wasted_input_tokens", String.valueOf(verdict.getTotalWastedInputTokens()));
        appendSummaryRow(csv, "verdict", "saving_fraction_of_spend", formatRatio(verdict.getSavingFractionOfSpend()));
        appendSummaryRow(csv, "verdict", "high_count", String.valueOf(verdict.getHighCount()));
        appendSummaryRow(csv, "verdict", "medium_count", String.valueOf(verdict.getMediumCount()));
        appendSummaryRow(csv, "verdict", "low_count", String.valueOf(verdict.getLowCount()));

        return csv.toString();
    }

    private void appendCallRow(StringBuilder csv, LlmOptimisationReport.Call call) {
        List<LlmOptimisationReport.ToolCall> toolCalls = call.getToolCalls();
        int toolCallCount = toolCalls != null ? toolCalls.size() : 0;
        csv.append(escape(String.valueOf(call.getIndex()))).append(',')
            .append(escape(call.getProvider())).append(',')
            .append(escape(call.getModel())).append(',')
            .append(call.getInputTokens()).append(',')
            .append(call.getOutputTokens()).append(',')
            .append(call.getCachedInputTokens()).append(',')
            .append(call.getReasoningTokens()).append(',')
            .append(formatUsd(call.getEstimatedCostUsd())).append(',')
            .append(call.isCostIsEstimated()).append(',')
            .append(call.getLatencyMs()).append(',')
            .append(toolCallCount).append(',')
            .append(escape(call.getFinishReason()))
            .append("\n");
    }

    private void appendSummaryRow(StringBuilder csv, String section, String metric, String value) {
        csv.append(escape(section)).append(',')
            .append(escape(metric)).append(',')
            .append(escape(value))
            .append("\n");
    }

    private static String formatUsd(double usd) {
        // pin Locale.US so the decimal separator is always '.', never a locale comma
        // (a comma would shift unescaped numeric columns and corrupt the CSV)
        return String.format(Locale.US, "%.6f", usd);
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.US, "%.4f", ratio);
    }

    /**
     * Escape a single CSV field per RFC 4180: wrap in double quotes and double
     * any embedded double quotes when the value contains a comma, double quote,
     * carriage return, or newline. A null value renders as an empty (unquoted)
     * field.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean mustQuote = value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
        if (!mustQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
