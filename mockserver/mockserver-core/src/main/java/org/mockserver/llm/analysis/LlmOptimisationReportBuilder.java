package org.mockserver.llm.analysis;

import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.client.LlmClient;
import org.mockserver.llm.client.LlmClientRegistry;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.llm.cost.LlmPricing;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;
import org.mockserver.model.Usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a deterministic {@link LlmOptimisationReport} from captured LLM
 * {@code FORWARDED_REQUEST} exchanges.
 * <p>
 * Pure and read-only: each exchange's request body is decoded with the
 * provider's {@link ProviderCodec}, the provider is detected with
 * {@link LlmProviderSniffer}, token usage is read from the response (via
 * {@link LlmClient#parseCompletionResponse}) or estimated from decoded text when
 * the provider returned no usage, and cost is estimated with {@link LlmPricing}.
 * No network and no LLM calls. The signal detectors ({@link OptimisationSignals})
 * run over the assembled calls.
 * <p>
 * Token estimation when usage is absent: a coarse {@code ~4 chars per token}
 * heuristic over the decoded prompt/completion text; flagged with
 * {@code costIsEstimated = true} so consumers know not to treat it as billed.
 */
public class LlmOptimisationReportBuilder {

    /** ~4 characters per token — a rough cross-provider estimate. */
    private static final double CHARS_PER_TOKEN = 4.0;

    /**
     * A transport-agnostic captured exchange: the forwarded request, the
     * upstream response, and the request timestamp in epoch millis (or
     * {@code null} when unknown). Lets the builder stay free of the event-log /
     * netty types.
     */
    public static final class CapturedExchange {
        private final HttpRequest request;
        private final HttpResponse response;
        private final Long latencyMs;

        public CapturedExchange(HttpRequest request, HttpResponse response, Long latencyMs) {
            this.request = request;
            this.response = response;
            this.latencyMs = latencyMs;
        }

        public HttpRequest getRequest() {
            return request;
        }

        public HttpResponse getResponse() {
            return response;
        }

        public Long getLatencyMs() {
            return latencyMs;
        }
    }

    /**
     * Build the report from the given exchanges. Non-LLM exchanges (no provider
     * detected) are ignored. The report's session key/grouping is supplied by
     * the caller (it has already grouped/filtered the traffic).
     *
     * @param exchanges        captured forwarded request/response pairs, in chronological order
     * @param sessionKey       the session/grouping key (e.g. {@code host:api.openai.com})
     * @param groupingBasis    how the traffic was grouped
     * @param redactedHeaders  header names that were/are redacted before rendering
     * @param redactedBodyFields body field names that were/are redacted before rendering
     */
    public LlmOptimisationReport build(List<CapturedExchange> exchanges,
                                       String sessionKey,
                                       LlmOptimisationReport.GroupingBasis groupingBasis,
                                       List<String> redactedHeaders,
                                       List<String> redactedBodyFields) {
        LlmOptimisationReport report = new LlmOptimisationReport();
        report.getSession()
            .setKey(sessionKey)
            .setGroupingBasis(groupingBasis != null ? groupingBasis : LlmOptimisationReport.GroupingBasis.PROXY_HOST);
        report.getRedaction()
            .setApplied(true)
            .setRedactedHeaders(redactedHeaders != null ? new ArrayList<>(redactedHeaders) : new ArrayList<>())
            .setRedactedBodyFields(redactedBodyFields != null ? new ArrayList<>(redactedBodyFields) : new ArrayList<>());

        if (exchanges == null || exchanges.isEmpty()) {
            return report;
        }

        Set<String> providers = new LinkedHashSet<>();
        Set<String> models = new LinkedHashSet<>();

        List<LlmOptimisationReport.Call> calls = report.getCalls();
        int index = 0;
        for (CapturedExchange exchange : exchanges) {
            HttpRequest request = exchange.getRequest();
            if (request == null) {
                continue;
            }
            // Defence-in-depth (the service already filters): skip response-less entries
            // such as the informational request-only LLM pre-log, which would otherwise
            // double-count calls and falsely trip DUPLICATE_CONSECUTIVE_CALL.
            if (exchange.getResponse() == null) {
                continue;
            }
            // detectForAnalysis (not sniff) so mocked LLM traffic on localhost is included,
            // consistent with the gate in LlmOptimisationReportService.
            Optional<Provider> providerOpt = LlmProviderSniffer.detectForAnalysis(request);
            if (!providerOpt.isPresent()) {
                continue; // not LLM traffic
            }
            Provider provider = providerOpt.get();
            LlmOptimisationReport.Call call = buildCall(index, request, exchange, provider);
            calls.add(call);
            index++;

            providers.add(call.getProvider());
            if (call.getModel() != null) {
                models.add(call.getModel());
            }
        }

        report.getSession()
            .setProviders(new ArrayList<>(providers))
            .setModels(new ArrayList<>(models));

        aggregateTotals(report);

        report.setSignals(new OptimisationSignals().detect(report.getCalls()));

        return report;
    }

    private LlmOptimisationReport.Call buildCall(int index, HttpRequest request, CapturedExchange exchange, Provider provider) {
        LlmOptimisationReport.Call call = new LlmOptimisationReport.Call();
        call.setIndex(index);
        call.setProvider(provider.name());
        if (request.getPath() != null) {
            call.setPath(request.getPath().getValue());
        }
        String model = modelFromRequest(request);

        // Decode the request conversation (best-effort).
        ParsedConversation conversation = decode(request, provider);
        List<ParsedMessage> messages = conversation != null ? conversation.getMessages() : Collections.emptyList();
        call.setMessageCount(messages.size());

        // System prompt fingerprint + token estimate (concatenate all system messages).
        String systemPromptText = systemPromptText(messages);
        if (systemPromptText != null && !systemPromptText.isEmpty()) {
            call.setSystemPromptFingerprint(fingerprint(systemPromptText));
            call.setSystemPromptTokens(estimateTokens(systemPromptText));
        }

        // Tool calls (assistant turns) with arg fingerprints and result token sizes.
        call.setToolCalls(buildToolCalls(messages));

        // Usage / cost from the response, else estimate from decoded text.
        applyUsageAndCost(call, exchange.getResponse(), provider, model, conversation);

        if (call.getModel() == null) {
            call.setModel(model);
        }
        if (exchange.getLatencyMs() != null && exchange.getLatencyMs() >= 0) {
            call.setLatencyMs(exchange.getLatencyMs());
        }
        return call;
    }

    private List<LlmOptimisationReport.ToolCall> buildToolCalls(List<ParsedMessage> messages) {
        List<LlmOptimisationReport.ToolCall> result = new ArrayList<>();
        // Map tool-call id -> result token size, from any TOOL message.
        java.util.Map<String, Long> resultTokensById = new java.util.HashMap<>();
        for (ParsedMessage message : messages) {
            if (message.getRole() == ParsedMessage.Role.TOOL) {
                for (java.util.Map.Entry<String, String> e : message.getToolResults().entrySet()) {
                    resultTokensById.put(e.getKey(), estimateTokens(e.getValue()));
                }
            }
        }
        for (ParsedMessage message : messages) {
            if (message.getRole() != ParsedMessage.Role.ASSISTANT) {
                continue;
            }
            for (ToolUse toolCall : message.getToolCalls()) {
                LlmOptimisationReport.ToolCall tc = new LlmOptimisationReport.ToolCall();
                tc.setName(toolCall.getName());
                String args = toolCall.getArguments();
                tc.setArgsFingerprint(args != null ? shortFingerprint(args) : null);
                if (toolCall.getId() != null && resultTokensById.containsKey(toolCall.getId())) {
                    tc.setResultTokens(resultTokensById.get(toolCall.getId()));
                }
                result.add(tc);
            }
        }
        return result;
    }

    private void applyUsageAndCost(LlmOptimisationReport.Call call, HttpResponse response, Provider provider,
                                   String model, ParsedConversation conversation) {
        Usage usage = parseUsage(response, provider);
        long inputTokens;
        long outputTokens;
        long cachedInputTokens = 0;
        long reasoningTokens = 0;
        boolean estimated;

        if (usage != null && (usage.getInputTokens() != null || usage.getOutputTokens() != null)) {
            inputTokens = usage.getInputTokens() == null ? 0 : usage.getInputTokens();
            outputTokens = usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
            cachedInputTokens = usage.getCachedInputTokens() == null ? 0 : usage.getCachedInputTokens();
            reasoningTokens = usage.getReasoningTokens() == null ? 0 : usage.getReasoningTokens();
            estimated = false;
        } else {
            inputTokens = estimateInputTokens(conversation);
            outputTokens = estimateOutputTokens(response, provider);
            estimated = true;
        }

        call.setInputTokens(inputTokens);
        call.setOutputTokens(outputTokens);
        call.setCachedInputTokens(cachedInputTokens);
        call.setReasoningTokens(reasoningTokens);
        call.setCostIsEstimated(estimated);

        Double cost = LlmPricing.estimateCostUsd(provider, model, inputTokens, outputTokens);
        call.setEstimatedCostUsd(cost == null ? 0.0 : round4(cost));
        call.setModel(model);

        if (response != null) {
            String finishReason = finishReason(response, provider);
            call.setFinishReason(finishReason);
        }
    }

    private void aggregateTotals(LlmOptimisationReport report) {
        LlmOptimisationReport.Totals totals = report.getTotals();
        long input = 0, output = 0, cached = 0, reasoning = 0, latency = 0;
        double cost = 0.0;
        int toolCalls = 0;
        boolean anyEstimated = false;
        for (LlmOptimisationReport.Call call : report.getCalls()) {
            input += call.getInputTokens();
            output += call.getOutputTokens();
            cached += call.getCachedInputTokens();
            reasoning += call.getReasoningTokens();
            latency += call.getLatencyMs();
            cost += call.getEstimatedCostUsd();
            toolCalls += call.getToolCalls().size();
            anyEstimated = anyEstimated || call.isCostIsEstimated();
        }
        totals.setCallCount(report.getCalls().size())
            .setInputTokens(input)
            .setOutputTokens(output)
            .setCachedInputTokens(cached)
            .setReasoningTokens(reasoning)
            .setTotalLatencyMs(latency)
            .setEstimatedCostUsd(round4(cost))
            .setCostIsEstimated(anyEstimated)
            .setToolCallCount(toolCalls);
    }

    // --- decoding / parsing helpers ---

    private ParsedConversation decode(HttpRequest request, Provider provider) {
        Optional<ProviderCodec> codecOpt = ProviderCodecRegistry.getInstance().lookup(provider);
        if (!codecOpt.isPresent()) {
            return null;
        }
        try {
            return codecOpt.get().decode(request);
        } catch (Exception e) {
            return null;
        }
    }

    private Usage parseUsage(HttpResponse response, Provider provider) {
        if (response == null) {
            return null;
        }
        Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
        if (!clientOpt.isPresent()) {
            return null;
        }
        try {
            Completion completion = clientOpt.get().parseCompletionResponse(response);
            return completion != null ? completion.getUsage() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String finishReason(HttpResponse response, Provider provider) {
        Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
        if (!clientOpt.isPresent()) {
            return null;
        }
        try {
            Completion completion = clientOpt.get().parseCompletionResponse(response);
            return completion != null ? completion.getStopReason() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String modelFromRequest(HttpRequest request) {
        // Prefer the request body's model field; LlmProviderSniffer already has a robust reader.
        return LlmProviderSniffer.extractModelFromRequest(request);
    }

    private static String systemPromptText(List<ParsedMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ParsedMessage message : messages) {
            if (message.getRole() == ParsedMessage.Role.SYSTEM && message.getTextContent() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(message.getTextContent());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static long estimateInputTokens(ParsedConversation conversation) {
        if (conversation == null) {
            return 0;
        }
        long chars = 0;
        for (ParsedMessage message : conversation.getMessages()) {
            if (message.getTextContent() != null) {
                chars += message.getTextContent().length();
            }
            for (ToolUse toolCall : message.getToolCalls()) {
                if (toolCall.getArguments() != null) {
                    chars += toolCall.getArguments().length();
                }
            }
            for (String result : message.getToolResults().values()) {
                if (result != null) {
                    chars += result.length();
                }
            }
        }
        return charsToTokens(chars);
    }

    private long estimateOutputTokens(HttpResponse response, Provider provider) {
        if (response == null) {
            return 0;
        }
        Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
        if (clientOpt.isPresent()) {
            try {
                Completion completion = clientOpt.get().parseCompletionResponse(response);
                if (completion != null && completion.getText() != null) {
                    return charsToTokens(completion.getText().length());
                }
            } catch (Exception e) {
                // fall through
            }
        }
        return 0;
    }

    private static long estimateTokens(String text) {
        return text == null ? 0 : charsToTokens(text.length());
    }

    private static long charsToTokens(long chars) {
        if (chars <= 0) {
            return 0;
        }
        return (long) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    // --- fingerprinting ---

    /** First 8 hex chars of SHA-256 of the (UTF-8) input. */
    static String fingerprint(String input) {
        return sha256Hex(input, 8);
    }

    /** First 4 hex chars — used for tool argument fingerprints (matches the contract sample). */
    static String shortFingerprint(String input) {
        return sha256Hex(input, 4);
    }

    private static String sha256Hex(String input, int hexChars) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
                if (sb.length() >= hexChars) {
                    break;
                }
            }
            return sb.substring(0, Math.min(hexChars, sb.length()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on a conformant JRE.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
