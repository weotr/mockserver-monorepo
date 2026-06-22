package org.mockserver.llm.analysis;

import org.mockserver.llm.analysis.LlmOptimisationReport.Call;
import org.mockserver.llm.analysis.LlmOptimisationReport.Signal;
import org.mockserver.llm.analysis.LlmOptimisationReport.ToolCall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic optimisation-signal detectors over the assembled calls of an
 * {@link LlmOptimisationReport}. Pure: same calls in, same signals out — no
 * network, no LLM, no randomness.
 * <p>
 * Each detector emits at most one {@link Signal} (per id) and gives the user's
 * downstream LLM a concrete lever: prompt caching, retrieval tools, replacing an
 * LLM-mediated step with a direct endpoint, trimming tool output, constraining
 * output, or removing duplicate retries. Thresholds are v1 constants (see §6 of
 * the contract — no new config beyond the max-calls bound applied upstream).
 * Cost figures defer to {@link org.mockserver.llm.cost.LlmPricing} via the
 * already-computed per-call cost, scaled by the wasted-token fraction.
 */
public class OptimisationSignals {

    /** A system/context block of this many tokens or more is "large" when resent. */
    static final long LARGE_CONTEXT_TOKEN_THRESHOLD = 2_000;

    /** A tool result of this many tokens or more is "oversized". */
    static final long OVERSIZED_TOOL_RESULT_TOKEN_THRESHOLD = 1_000;

    /** Output tokens at or above this absolute count are "bloated". */
    static final long OUTPUT_BLOAT_ABSOLUTE_THRESHOLD = 1_500;

    /** Output tokens this many times the median (or more) are "bloated". */
    static final double OUTPUT_BLOAT_MEDIAN_MULTIPLE = 3.0;

    public List<Signal> detect(List<Call> calls) {
        List<Signal> signals = new ArrayList<>();
        if (calls == null || calls.isEmpty()) {
            return signals;
        }
        addIfPresent(signals, repeatedSystemPrompt(calls));
        addIfPresent(signals, largeStaticContextResent(calls));
        addIfPresent(signals, deterministicToolCall(calls));
        addIfPresent(signals, oversizedToolResult(calls));
        addIfPresent(signals, outputTokenBloat(calls));
        addIfPresent(signals, duplicateConsecutiveCall(calls));
        // HIGH first, then MEDIUM, then LOW; stable within a severity (insertion order).
        signals.sort(Comparator.comparingInt(s -> severityRank(s.getSeverity())));
        return signals;
    }

    private static void addIfPresent(List<Signal> signals, Signal signal) {
        if (signal != null) {
            signals.add(signal);
        }
    }

    private static int severityRank(String severity) {
        if ("HIGH".equals(severity)) {
            return 0;
        }
        if ("MEDIUM".equals(severity)) {
            return 1;
        }
        return 2;
    }

    // --- REPEATED_SYSTEM_PROMPT ---

    private Signal repeatedSystemPrompt(List<Call> calls) {
        // Group calls by non-null system prompt fingerprint.
        Map<String, List<Integer>> byFingerprint = new LinkedHashMap<>();
        Map<String, Long> tokensByFingerprint = new LinkedHashMap<>();
        for (Call call : calls) {
            String fp = call.getSystemPromptFingerprint();
            if (fp == null) {
                continue;
            }
            byFingerprint.computeIfAbsent(fp, k -> new ArrayList<>()).add(call.getIndex());
            tokensByFingerprint.put(fp, call.getSystemPromptTokens());
        }
        String worstFp = null;
        for (Map.Entry<String, List<Integer>> e : byFingerprint.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            if (worstFp == null || wastedTokens(tokensByFingerprint, byFingerprint, e.getKey())
                > wastedTokens(tokensByFingerprint, byFingerprint, worstFp)) {
                worstFp = e.getKey();
            }
        }
        if (worstFp == null) {
            return null;
        }
        List<Integer> affected = byFingerprint.get(worstFp);
        long promptTokens = tokensByFingerprint.get(worstFp);
        int repeats = affected.size();
        long wasted = promptTokens * (repeats - 1L);
        Double saving = savingForWastedInput(calls, affected, wasted);

        return new Signal()
            .setId("REPEATED_SYSTEM_PROMPT")
            .setSeverity(repeats >= 3 && promptTokens >= 1_000 ? "HIGH" : "MEDIUM")
            .setTitle("Identical " + formatTokens(promptTokens) + "-token system prompt resent on all "
                + repeats + " calls")
            .setDetail("The same system prompt (fingerprint " + worstFp + ") was sent on " + repeats + "/"
                + calls.size() + " calls, re-paying for the same input tokens each turn.")
            .setAffectedCalls(affected)
            .setEstimatedWastedInputTokens(wasted)
            .setEstimatedSavingUsd(saving)
            .setRecommendation("Enable provider prompt caching, or move the static context into a retrieval tool so it is not resent every turn.");
    }

    private static long wastedTokens(Map<String, Long> tokensByFp, Map<String, List<Integer>> byFp, String fp) {
        return tokensByFp.get(fp) * (byFp.get(fp).size() - 1L);
    }

    // --- LARGE_STATIC_CONTEXT_RESENT ---

    private Signal largeStaticContextResent(List<Call> calls) {
        // A large system prompt repeated across >= 2 calls is a large static context resent.
        Map<String, List<Integer>> byFingerprint = new LinkedHashMap<>();
        Map<String, Long> tokensByFingerprint = new LinkedHashMap<>();
        for (Call call : calls) {
            String fp = call.getSystemPromptFingerprint();
            if (fp == null || call.getSystemPromptTokens() < LARGE_CONTEXT_TOKEN_THRESHOLD) {
                continue;
            }
            byFingerprint.computeIfAbsent(fp, k -> new ArrayList<>()).add(call.getIndex());
            tokensByFingerprint.put(fp, call.getSystemPromptTokens());
        }
        for (Map.Entry<String, List<Integer>> e : byFingerprint.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            List<Integer> affected = e.getValue();
            long tokens = tokensByFingerprint.get(e.getKey());
            long wasted = tokens * (affected.size() - 1L);
            return new Signal()
                .setId("LARGE_STATIC_CONTEXT_RESENT")
                .setSeverity("HIGH")
                .setTitle("Large " + formatTokens(tokens) + "-token static context resent across "
                    + affected.size() + " calls")
                .setDetail("A " + formatTokens(tokens) + "-token context block (fingerprint " + e.getKey()
                    + ") was resent on " + affected.size() + " calls instead of being cached or retrieved on demand.")
                .setAffectedCalls(affected)
                .setEstimatedWastedInputTokens(wasted)
                .setEstimatedSavingUsd(savingForWastedInput(calls, affected, wasted))
                .setRecommendation("Move the large static context into a retrieval tool or enable prompt caching so it is sent once, not every turn.");
        }
        return null;
    }

    // --- DETERMINISTIC_TOOL_CALL ---

    private Signal deterministicToolCall(List<Call> calls) {
        // Same tool name + same args fingerprint across >= 2 calls => deterministic.
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (Call call : calls) {
            for (ToolCall tc : call.getToolCalls()) {
                if (tc.getName() == null || tc.getArgsFingerprint() == null) {
                    continue;
                }
                String key = tc.getName() + "|" + tc.getArgsFingerprint();
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(call.getIndex());
                nameByKey.put(key, tc.getName());
            }
        }
        for (Map.Entry<String, List<Integer>> e : byKey.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            List<Integer> affected = dedupeSorted(e.getValue());
            return new Signal()
                .setId("DETERMINISTIC_TOOL_CALL")
                .setSeverity("MEDIUM")
                .setTitle("Tool '" + nameByKey.get(e.getKey()) + "' called with identical arguments "
                    + e.getValue().size() + " times")
                .setDetail("The tool '" + nameByKey.get(e.getKey()) + "' was invoked with the same arguments across "
                    + affected.size() + " calls, so its result is deterministic and the round-trip through the model is avoidable.")
                .setAffectedCalls(affected)
                .setEstimatedWastedInputTokens(null)
                .setEstimatedSavingUsd(null)
                .setRecommendation("Replace this LLM-mediated step with a direct HTTP or MCP endpoint call and feed the result back deterministically.");
        }
        return null;
    }

    // --- OVERSIZED_TOOL_RESULT ---

    private Signal oversizedToolResult(List<Call> calls) {
        List<Integer> affected = new ArrayList<>();
        long maxResult = 0;
        String worstTool = null;
        for (Call call : calls) {
            for (ToolCall tc : call.getToolCalls()) {
                if (tc.getResultTokens() >= OVERSIZED_TOOL_RESULT_TOKEN_THRESHOLD) {
                    if (!affected.contains(call.getIndex())) {
                        affected.add(call.getIndex());
                    }
                    if (tc.getResultTokens() > maxResult) {
                        maxResult = tc.getResultTokens();
                        worstTool = tc.getName();
                    }
                }
            }
        }
        if (affected.isEmpty()) {
            return null;
        }
        return new Signal()
            .setId("OVERSIZED_TOOL_RESULT")
            .setSeverity("MEDIUM")
            .setTitle("Oversized tool result (" + formatTokens(maxResult) + " tokens"
                + (worstTool != null ? " from '" + worstTool + "'" : "") + ") inflating downstream input")
            .setDetail("A tool returned " + formatTokens(maxResult) + " tokens, which are re-sent as input on every"
                + " subsequent turn, inflating cost.")
            .setAffectedCalls(affected)
            .setEstimatedWastedInputTokens(null)
            .setEstimatedSavingUsd(null)
            .setRecommendation("Trim or summarise the tool output before returning it to the model so only the relevant fields are sent.");
    }

    // --- OUTPUT_TOKEN_BLOAT ---

    private Signal outputTokenBloat(List<Call> calls) {
        List<Long> outputs = new ArrayList<>();
        for (Call call : calls) {
            outputs.add(call.getOutputTokens());
        }
        double median = median(outputs);
        List<Integer> affected = new ArrayList<>();
        long maxOutput = 0;
        for (Call call : calls) {
            long out = call.getOutputTokens();
            boolean aboveAbsolute = out >= OUTPUT_BLOAT_ABSOLUTE_THRESHOLD;
            boolean aboveMedian = median > 0 && out >= median * OUTPUT_BLOAT_MEDIAN_MULTIPLE;
            if (aboveAbsolute || aboveMedian) {
                affected.add(call.getIndex());
                maxOutput = Math.max(maxOutput, out);
            }
        }
        if (affected.isEmpty()) {
            return null;
        }
        return new Signal()
            .setId("OUTPUT_TOKEN_BLOAT")
            .setSeverity("LOW")
            .setTitle("Output token bloat: up to " + formatTokens(maxOutput) + " output tokens on a single call")
            .setDetail("One or more calls produced far more output than the median (" + formatTokens((long) median)
                + " tokens), which is expensive and often unnecessary.")
            .setAffectedCalls(affected)
            .setEstimatedWastedInputTokens(null)
            .setEstimatedSavingUsd(null)
            .setRecommendation("Constrain output with max_tokens or a stricter response_format/JSON schema so the model returns only what is needed.");
    }

    // --- DUPLICATE_CONSECUTIVE_CALL ---

    private Signal duplicateConsecutiveCall(List<Call> calls) {
        // Near-identical consecutive calls: same path, model, message count and system prompt
        // fingerprint suggest a retry of the same request. A call is "wasted" only when it
        // duplicates its immediate predecessor (so the first call of each duplicate run is not
        // counted), summed correctly across multiple independent runs.
        List<Integer> affected = new ArrayList<>();
        java.util.Set<Integer> affectedSet = new java.util.LinkedHashSet<>();
        long wasted = 0;
        for (int i = 1; i < calls.size(); i++) {
            Call prev = calls.get(i - 1);
            Call cur = calls.get(i);
            if (sameish(prev, cur)) {
                if (affectedSet.add(prev.getIndex())) {
                    affected.add(prev.getIndex());
                }
                if (affectedSet.add(cur.getIndex())) {
                    affected.add(cur.getIndex());
                }
                // cur is a retry of prev within this run, so its input tokens are wasted.
                wasted += cur.getInputTokens();
            }
        }
        if (affected.isEmpty()) {
            return null;
        }
        return new Signal()
            .setId("DUPLICATE_CONSECUTIVE_CALL")
            .setSeverity("MEDIUM")
            .setTitle("Duplicate consecutive calls detected (likely retries)")
            .setDetail("Consecutive calls with near-identical request shape were observed, suggesting retries that re-pay for the same work.")
            .setAffectedCalls(affected)
            .setEstimatedWastedInputTokens(wasted > 0 ? wasted : null)
            .setEstimatedSavingUsd(null)
            .setRecommendation("De-duplicate or cache identical requests, and only retry on genuine transient errors with backoff.");
    }

    private static boolean sameish(Call a, Call b) {
        return java.util.Objects.equals(a.getPath(), b.getPath())
            && java.util.Objects.equals(a.getModel(), b.getModel())
            && a.getMessageCount() == b.getMessageCount()
            && java.util.Objects.equals(a.getSystemPromptFingerprint(), b.getSystemPromptFingerprint())
            && a.getInputTokens() == b.getInputTokens();
    }

    // --- shared helpers ---

    /**
     * Scale the total session cost by the wasted-input fraction of the affected
     * calls to get a rough USD saving. Returns null when cost cannot be attributed.
     */
    private static Double savingForWastedInput(List<Call> calls, List<Integer> affected, long wastedInputTokens) {
        if (wastedInputTokens <= 0) {
            return null;
        }
        // Sum the affected calls' input tokens and their cost; attribute the saving proportionally.
        long affectedInput = 0;
        double affectedCost = 0.0;
        for (Call call : calls) {
            if (affected.contains(call.getIndex())) {
                affectedInput += call.getInputTokens();
                affectedCost += call.getEstimatedCostUsd();
            }
        }
        if (affectedInput <= 0 || affectedCost <= 0.0) {
            return null;
        }
        double fraction = Math.min(1.0, (double) wastedInputTokens / affectedInput);
        return LlmOptimisationReportBuilder.round4(affectedCost * fraction);
    }

    private static List<Integer> dedupeSorted(List<Integer> in) {
        List<Integer> out = new ArrayList<>(new java.util.LinkedHashSet<>(in));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String formatTokens(long tokens) {
        return String.format("%,d", tokens);
    }
}
