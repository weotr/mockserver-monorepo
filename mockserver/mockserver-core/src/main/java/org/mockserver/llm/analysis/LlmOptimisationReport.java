package org.mockserver.llm.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured, deterministic optimisation report for a captured LLM session
 * (the JSON bundle "B" of the LLM optimisation export feature). Built by
 * {@link LlmOptimisationReportBuilder} from captured {@code FORWARDED_REQUEST}
 * exchanges and rendered to a copy-paste Markdown brief by
 * {@link LlmOptimisationBriefRenderer}.
 * <p>
 * Pure data — no behaviour, no network, no LLM. Serialised to JSON by the
 * control-plane endpoint / MCP tool. Field names and nesting are the FROZEN
 * contract; do not rename without updating the UI and docs streams.
 */
public class LlmOptimisationReport {

    /** Bumped if the JSON shape changes incompatibly. */
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private String generatedBy = "mockserver";
    private Session session = new Session();
    private Totals totals = new Totals();
    private List<Call> calls = new ArrayList<>();
    private List<Signal> signals = new ArrayList<>();
    private Redaction redaction = new Redaction();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public LlmOptimisationReport setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
        return this;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public LlmOptimisationReport setGeneratedBy(String generatedBy) {
        this.generatedBy = generatedBy;
        return this;
    }

    public Session getSession() {
        return session;
    }

    public LlmOptimisationReport setSession(Session session) {
        this.session = session;
        return this;
    }

    public Totals getTotals() {
        return totals;
    }

    public LlmOptimisationReport setTotals(Totals totals) {
        this.totals = totals;
        return this;
    }

    public List<Call> getCalls() {
        return calls;
    }

    public LlmOptimisationReport setCalls(List<Call> calls) {
        this.calls = calls;
        return this;
    }

    public List<Signal> getSignals() {
        return signals;
    }

    public LlmOptimisationReport setSignals(List<Signal> signals) {
        this.signals = signals;
        return this;
    }

    public Redaction getRedaction() {
        return redaction;
    }

    public LlmOptimisationReport setRedaction(Redaction redaction) {
        this.redaction = redaction;
        return this;
    }

    /** How the captured traffic was grouped into one report. */
    public enum GroupingBasis {
        ISOLATION_KEY,
        PROXY_HOST
    }

    /** Identity and scope of the session this report covers. */
    public static class Session {
        private String key;
        private GroupingBasis groupingBasis = GroupingBasis.PROXY_HOST;
        private List<String> providers = new ArrayList<>();
        private List<String> models = new ArrayList<>();

        public String getKey() {
            return key;
        }

        public Session setKey(String key) {
            this.key = key;
            return this;
        }

        public GroupingBasis getGroupingBasis() {
            return groupingBasis;
        }

        public Session setGroupingBasis(GroupingBasis groupingBasis) {
            this.groupingBasis = groupingBasis;
            return this;
        }

        public List<String> getProviders() {
            return providers;
        }

        public Session setProviders(List<String> providers) {
            this.providers = providers;
            return this;
        }

        public List<String> getModels() {
            return models;
        }

        public Session setModels(List<String> models) {
            this.models = models;
            return this;
        }
    }

    /** Aggregated counts across every included call. */
    public static class Totals {
        private int callCount;
        private long inputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private long reasoningTokens;
        private double estimatedCostUsd;
        private boolean costIsEstimated;
        private long totalLatencyMs;
        private int toolCallCount;

        public int getCallCount() {
            return callCount;
        }

        public Totals setCallCount(int callCount) {
            this.callCount = callCount;
            return this;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public Totals setInputTokens(long inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        public Totals setOutputTokens(long outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        public long getCachedInputTokens() {
            return cachedInputTokens;
        }

        public Totals setCachedInputTokens(long cachedInputTokens) {
            this.cachedInputTokens = cachedInputTokens;
            return this;
        }

        public long getReasoningTokens() {
            return reasoningTokens;
        }

        public Totals setReasoningTokens(long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        public double getEstimatedCostUsd() {
            return estimatedCostUsd;
        }

        public Totals setEstimatedCostUsd(double estimatedCostUsd) {
            this.estimatedCostUsd = estimatedCostUsd;
            return this;
        }

        public boolean isCostIsEstimated() {
            return costIsEstimated;
        }

        public Totals setCostIsEstimated(boolean costIsEstimated) {
            this.costIsEstimated = costIsEstimated;
            return this;
        }

        public long getTotalLatencyMs() {
            return totalLatencyMs;
        }

        public Totals setTotalLatencyMs(long totalLatencyMs) {
            this.totalLatencyMs = totalLatencyMs;
            return this;
        }

        public int getToolCallCount() {
            return toolCallCount;
        }

        public Totals setToolCallCount(int toolCallCount) {
            this.toolCallCount = toolCallCount;
            return this;
        }
    }

    /** One captured LLM call (request/response exchange). */
    public static class Call {
        private int index;
        private String path;
        private String provider;
        private String model;
        private long inputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private long reasoningTokens;
        private double estimatedCostUsd;
        private boolean costIsEstimated;
        private long latencyMs;
        private String finishReason;
        private int messageCount;
        private long systemPromptTokens;
        private String systemPromptFingerprint;
        private List<ToolCall> toolCalls = new ArrayList<>();

        public int getIndex() {
            return index;
        }

        public Call setIndex(int index) {
            this.index = index;
            return this;
        }

        public String getPath() {
            return path;
        }

        public Call setPath(String path) {
            this.path = path;
            return this;
        }

        public String getProvider() {
            return provider;
        }

        public Call setProvider(String provider) {
            this.provider = provider;
            return this;
        }

        public String getModel() {
            return model;
        }

        public Call setModel(String model) {
            this.model = model;
            return this;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public Call setInputTokens(long inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        public Call setOutputTokens(long outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        public long getCachedInputTokens() {
            return cachedInputTokens;
        }

        public Call setCachedInputTokens(long cachedInputTokens) {
            this.cachedInputTokens = cachedInputTokens;
            return this;
        }

        public long getReasoningTokens() {
            return reasoningTokens;
        }

        public Call setReasoningTokens(long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        public double getEstimatedCostUsd() {
            return estimatedCostUsd;
        }

        public Call setEstimatedCostUsd(double estimatedCostUsd) {
            this.estimatedCostUsd = estimatedCostUsd;
            return this;
        }

        public boolean isCostIsEstimated() {
            return costIsEstimated;
        }

        public Call setCostIsEstimated(boolean costIsEstimated) {
            this.costIsEstimated = costIsEstimated;
            return this;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public Call setLatencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public Call setFinishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public int getMessageCount() {
            return messageCount;
        }

        public Call setMessageCount(int messageCount) {
            this.messageCount = messageCount;
            return this;
        }

        public long getSystemPromptTokens() {
            return systemPromptTokens;
        }

        public Call setSystemPromptTokens(long systemPromptTokens) {
            this.systemPromptTokens = systemPromptTokens;
            return this;
        }

        public String getSystemPromptFingerprint() {
            return systemPromptFingerprint;
        }

        public Call setSystemPromptFingerprint(String systemPromptFingerprint) {
            this.systemPromptFingerprint = systemPromptFingerprint;
            return this;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public Call setToolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }
    }

    /** A single assistant tool call observed in a call. */
    public static class ToolCall {
        private String name;
        private String argsFingerprint;
        private long resultTokens;

        public String getName() {
            return name;
        }

        public ToolCall setName(String name) {
            this.name = name;
            return this;
        }

        public String getArgsFingerprint() {
            return argsFingerprint;
        }

        public ToolCall setArgsFingerprint(String argsFingerprint) {
            this.argsFingerprint = argsFingerprint;
            return this;
        }

        public long getResultTokens() {
            return resultTokens;
        }

        public ToolCall setResultTokens(long resultTokens) {
            this.resultTokens = resultTokens;
            return this;
        }
    }

    /** A deterministic optimisation lead. */
    public static class Signal {
        private String id;
        private String severity;
        private String title;
        private String detail;
        private List<Integer> affectedCalls = new ArrayList<>();
        private Long estimatedWastedInputTokens;
        private Double estimatedSavingUsd;
        private String recommendation;

        public String getId() {
            return id;
        }

        public Signal setId(String id) {
            this.id = id;
            return this;
        }

        public String getSeverity() {
            return severity;
        }

        public Signal setSeverity(String severity) {
            this.severity = severity;
            return this;
        }

        public String getTitle() {
            return title;
        }

        public Signal setTitle(String title) {
            this.title = title;
            return this;
        }

        public String getDetail() {
            return detail;
        }

        public Signal setDetail(String detail) {
            this.detail = detail;
            return this;
        }

        public List<Integer> getAffectedCalls() {
            return affectedCalls;
        }

        public Signal setAffectedCalls(List<Integer> affectedCalls) {
            this.affectedCalls = affectedCalls;
            return this;
        }

        public Long getEstimatedWastedInputTokens() {
            return estimatedWastedInputTokens;
        }

        public Signal setEstimatedWastedInputTokens(Long estimatedWastedInputTokens) {
            this.estimatedWastedInputTokens = estimatedWastedInputTokens;
            return this;
        }

        public Double getEstimatedSavingUsd() {
            return estimatedSavingUsd;
        }

        public Signal setEstimatedSavingUsd(Double estimatedSavingUsd) {
            this.estimatedSavingUsd = estimatedSavingUsd;
            return this;
        }

        public String getRecommendation() {
            return recommendation;
        }

        public Signal setRecommendation(String recommendation) {
            this.recommendation = recommendation;
            return this;
        }
    }

    /** What redaction was applied before the report/brief was produced. */
    public static class Redaction {
        private boolean applied = true;
        private List<String> redactedHeaders = new ArrayList<>();
        private List<String> redactedBodyFields = new ArrayList<>();

        public boolean isApplied() {
            return applied;
        }

        public Redaction setApplied(boolean applied) {
            this.applied = applied;
            return this;
        }

        public List<String> getRedactedHeaders() {
            return redactedHeaders;
        }

        public Redaction setRedactedHeaders(List<String> redactedHeaders) {
            this.redactedHeaders = redactedHeaders;
            return this;
        }

        public List<String> getRedactedBodyFields() {
            return redactedBodyFields;
        }

        public Redaction setRedactedBodyFields(List<String> redactedBodyFields) {
            this.redactedBodyFields = redactedBodyFields;
            return this;
        }
    }
}
