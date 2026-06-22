package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.llm.analysis.LlmOptimisationReport.Call;
import org.mockserver.llm.analysis.LlmOptimisationReport.Signal;
import org.mockserver.llm.analysis.LlmOptimisationReport.ToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OptimisationSignalsTest {

    private final OptimisationSignals signals = new OptimisationSignals();

    private static Call call(int index) {
        return new Call().setIndex(index).setModel("gpt-4o-2024-08-06").setPath("/v1/chat/completions");
    }

    private static Optional<Signal> findSignal(List<Signal> list, String id) {
        return list.stream().filter(s -> id.equals(s.getId())).findFirst();
    }

    // --- REPEATED_SYSTEM_PROMPT ---

    @Test
    public void repeatedSystemPromptDetectedWhenFingerprintRepeats() {
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(call(i).setSystemPromptFingerprint("a1b2c3d4").setSystemPromptTokens(5400)
                .setInputTokens(8000).setEstimatedCostUsd(0.02));
        }
        Optional<Signal> signal = findSignal(signals.detect(calls), "REPEATED_SYSTEM_PROMPT");
        assertTrue(signal.isPresent());
        assertEquals("HIGH", signal.get().getSeverity());
        assertThat(signal.get().getAffectedCalls(), contains(0, 1, 2, 3, 4, 5));
        // wasted = 5400 * (6-1)
        assertEquals(Long.valueOf(27000), signal.get().getEstimatedWastedInputTokens());
        assertNotNull(signal.get().getEstimatedSavingUsd());
    }

    @Test
    public void repeatedSystemPromptNotDetectedForDistinctPrompts() {
        List<Call> calls = Arrays.asList(
            call(0).setSystemPromptFingerprint("aaaa1111").setSystemPromptTokens(100),
            call(1).setSystemPromptFingerprint("bbbb2222").setSystemPromptTokens(100));
        assertNull(findSignal(signals.detect(calls), "REPEATED_SYSTEM_PROMPT").orElse(null));
    }

    // --- LARGE_STATIC_CONTEXT_RESENT ---

    @Test
    public void largeStaticContextResentDetected() {
        List<Call> calls = Arrays.asList(
            call(0).setSystemPromptFingerprint("big00001").setSystemPromptTokens(5000).setInputTokens(6000).setEstimatedCostUsd(0.01),
            call(1).setSystemPromptFingerprint("big00001").setSystemPromptTokens(5000).setInputTokens(6000).setEstimatedCostUsd(0.01));
        Optional<Signal> signal = findSignal(signals.detect(calls), "LARGE_STATIC_CONTEXT_RESENT");
        assertTrue(signal.isPresent());
        assertEquals("HIGH", signal.get().getSeverity());
    }

    @Test
    public void smallContextDoesNotTriggerLargeStaticContext() {
        List<Call> calls = Arrays.asList(
            call(0).setSystemPromptFingerprint("sm000001").setSystemPromptTokens(100),
            call(1).setSystemPromptFingerprint("sm000001").setSystemPromptTokens(100));
        assertNull(findSignal(signals.detect(calls), "LARGE_STATIC_CONTEXT_RESENT").orElse(null));
    }

    // --- DETERMINISTIC_TOOL_CALL ---

    @Test
    public void deterministicToolCallDetectedForSameNameAndArgs() {
        List<Call> calls = new ArrayList<>();
        calls.add(call(0).setToolCalls(Collections.singletonList(
            new ToolCall().setName("get_weather").setArgsFingerprint("e5f6"))));
        calls.add(call(1).setToolCalls(Collections.singletonList(
            new ToolCall().setName("get_weather").setArgsFingerprint("e5f6"))));
        Optional<Signal> signal = findSignal(signals.detect(calls), "DETERMINISTIC_TOOL_CALL");
        assertTrue(signal.isPresent());
        assertThat(signal.get().getAffectedCalls(), contains(0, 1));
    }

    @Test
    public void differentArgsDoesNotTriggerDeterministicToolCall() {
        List<Call> calls = Arrays.asList(
            call(0).setToolCalls(Collections.singletonList(new ToolCall().setName("get_weather").setArgsFingerprint("aaaa"))),
            call(1).setToolCalls(Collections.singletonList(new ToolCall().setName("get_weather").setArgsFingerprint("bbbb"))));
        assertNull(findSignal(signals.detect(calls), "DETERMINISTIC_TOOL_CALL").orElse(null));
    }

    // --- OVERSIZED_TOOL_RESULT ---

    @Test
    public void oversizedToolResultDetected() {
        List<Call> calls = Collections.singletonList(
            call(0).setToolCalls(Collections.singletonList(
                new ToolCall().setName("fetch_docs").setArgsFingerprint("c1c1").setResultTokens(5000))));
        Optional<Signal> signal = findSignal(signals.detect(calls), "OVERSIZED_TOOL_RESULT");
        assertTrue(signal.isPresent());
        assertThat(signal.get().getAffectedCalls(), contains(0));
    }

    @Test
    public void smallToolResultDoesNotTrigger() {
        List<Call> calls = Collections.singletonList(
            call(0).setToolCalls(Collections.singletonList(
                new ToolCall().setName("fetch_docs").setResultTokens(30))));
        assertNull(findSignal(signals.detect(calls), "OVERSIZED_TOOL_RESULT").orElse(null));
    }

    // --- OUTPUT_TOKEN_BLOAT ---

    @Test
    public void outputTokenBloatDetectedAbsolute() {
        List<Call> calls = Collections.singletonList(call(0).setOutputTokens(5000));
        Optional<Signal> signal = findSignal(signals.detect(calls), "OUTPUT_TOKEN_BLOAT");
        assertTrue(signal.isPresent());
    }

    @Test
    public void outputTokenBloatDetectedAboveMedian() {
        List<Call> calls = Arrays.asList(
            call(0).setOutputTokens(100),
            call(1).setOutputTokens(100),
            call(2).setOutputTokens(400)); // 4x median of 100
        Optional<Signal> signal = findSignal(signals.detect(calls), "OUTPUT_TOKEN_BLOAT");
        assertTrue(signal.isPresent());
        assertThat(signal.get().getAffectedCalls(), contains(2));
    }

    @Test
    public void evenOutputDoesNotTriggerBloat() {
        List<Call> calls = Arrays.asList(call(0).setOutputTokens(100), call(1).setOutputTokens(120));
        assertNull(findSignal(signals.detect(calls), "OUTPUT_TOKEN_BLOAT").orElse(null));
    }

    // --- DUPLICATE_CONSECUTIVE_CALL ---

    @Test
    public void duplicateConsecutiveCallDetected() {
        Call a = call(0).setMessageCount(3).setSystemPromptFingerprint("dup00001").setInputTokens(500);
        Call b = call(1).setMessageCount(3).setSystemPromptFingerprint("dup00001").setInputTokens(500);
        Optional<Signal> signal = findSignal(signals.detect(Arrays.asList(a, b)), "DUPLICATE_CONSECUTIVE_CALL");
        assertTrue(signal.isPresent());
        assertThat(signal.get().getAffectedCalls(), contains(0, 1));
    }

    @Test
    public void duplicateWastedTokensCountedPerGroupNotAcrossGroups() {
        // Two independent duplicate runs: (0,1) and (3,4), with a distinct call 2 between.
        Call a0 = call(0).setMessageCount(3).setSystemPromptFingerprint("g1").setInputTokens(500);
        Call a1 = call(1).setMessageCount(3).setSystemPromptFingerprint("g1").setInputTokens(500);
        Call b2 = call(2).setMessageCount(7).setSystemPromptFingerprint("mid").setInputTokens(999);
        Call c3 = call(3).setMessageCount(4).setSystemPromptFingerprint("g2").setInputTokens(700);
        Call c4 = call(4).setMessageCount(4).setSystemPromptFingerprint("g2").setInputTokens(700);
        Signal signal = findSignal(signals.detect(Arrays.asList(a0, a1, b2, c3, c4)),
            "DUPLICATE_CONSECUTIVE_CALL").orElseThrow(AssertionError::new);
        assertThat(signal.getAffectedCalls(), contains(0, 1, 3, 4));
        // wasted = the retry of each group (call 1 + call 4), NOT call 3.
        assertEquals(Long.valueOf(1200), signal.getEstimatedWastedInputTokens());
    }

    @Test
    public void nonDuplicateConsecutiveDoesNotTrigger() {
        Call a = call(0).setMessageCount(3).setInputTokens(500);
        Call b = call(1).setMessageCount(5).setInputTokens(900);
        assertNull(findSignal(signals.detect(Arrays.asList(a, b)), "DUPLICATE_CONSECUTIVE_CALL").orElse(null));
    }

    // --- ordering + empty ---

    @Test
    public void signalsAreSortedHighSeverityFirst() {
        List<Call> calls = new ArrayList<>();
        // Repeated system prompt (HIGH) + output bloat (LOW) on a separate call.
        for (int i = 0; i < 3; i++) {
            calls.add(call(i).setSystemPromptFingerprint("hi000001").setSystemPromptTokens(2000)
                .setInputTokens(3000).setEstimatedCostUsd(0.01).setOutputTokens(100));
        }
        calls.add(call(3).setOutputTokens(9000)); // LOW bloat
        List<Signal> result = signals.detect(calls);
        assertTrue(result.size() >= 2);
        // first must be HIGH
        assertEquals("HIGH", result.get(0).getSeverity());
    }

    @Test
    public void noCallsYieldNoSignals() {
        assertThat(signals.detect(Collections.emptyList()), is(empty()));
        assertThat(signals.detect(null), is(empty()));
    }
}
