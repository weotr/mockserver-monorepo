package org.mockserver.mock.drift;

import com.fasterxml.jackson.databind.JsonNode;
import org.mockserver.llm.drift.StructuralShapeDiff;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.time.TimeService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares a real forwarded response against a matching stub expectation's
 * configured response and emits {@link DriftRecord}s for any structural
 * discrepancies (status code, headers, JSON schema).
 */
public class DriftAnalyzer {

    private static final DriftAnalyzer INSTANCE = new DriftAnalyzer(DriftStore.getInstance());

    /** Headers that change per-request and should not trigger drift alerts. */
    private static final Set<String> SKIP_HEADERS = Set.of(
        "date", "x-request-id", "content-length", "transfer-encoding",
        "connection", "keep-alive", "server"
    );

    private final DriftStore store;
    private volatile SemanticDriftExtension semanticExtension;
    private volatile long responseTimeThresholdMs;

    public DriftAnalyzer(DriftStore store) {
        this.store = store;
    }

    /**
     * Set the optional semantic drift extension. When non-null and available,
     * structural drift records are enriched with LLM-classified severity.
     */
    public void setSemanticExtension(SemanticDriftExtension extension) {
        this.semanticExtension = extension;
    }

    /**
     * Set the p95 response time threshold (in milliseconds) above which a
     * PERFORMANCE drift record is emitted. Zero or negative disables.
     */
    public void setResponseTimeThresholdMs(long thresholdMs) {
        this.responseTimeThresholdMs = thresholdMs;
    }

    public static DriftAnalyzer getInstance() {
        return INSTANCE;
    }

    /**
     * Analyse the real forwarded response against the expectation's stub response.
     * If the expectation's action is not an {@link HttpResponse} (e.g. it is a forward
     * action), this method is a no-op.
     *
     * @param expectation  the matched expectation (may be null)
     * @param realResponse the response received from the upstream service (may be null)
     */
    public void analyse(Expectation expectation, HttpResponse realResponse) {
        if (expectation == null || realResponse == null) {
            return;
        }
        if (!(expectation.getAction() instanceof HttpResponse stubResponse)) {
            return;
        }

        String expectationId = expectation.getId();
        long now = TimeService.currentTimeMillis();

        List<DriftRecord> collected = new java.util.ArrayList<>();
        collectStatus(collected, expectationId, stubResponse, realResponse, now);
        collectHeaders(collected, expectationId, stubResponse, realResponse, now);
        collectJsonSchema(collected, expectationId, stubResponse, realResponse, now);

        // Semantic enrichment (best-effort, never fails the pipeline)
        SemanticDriftExtension ext = this.semanticExtension;
        if (ext != null && ext.isAvailable() && !collected.isEmpty()) {
            ext.enrich(collected, expectationId, stubResponse, realResponse);
        }

        // Store all collected records
        for (DriftRecord record : collected) {
            store.add(record);
            DriftAlertNotifier.getInstance().onDriftStored(record);
        }
    }

    private void collectStatus(List<DriftRecord> collected, String expectationId, HttpResponse stubResponse, HttpResponse realResponse, long now) {
        Integer stubStatus = stubResponse.getStatusCode();
        Integer realStatus = realResponse.getStatusCode();
        if (stubStatus != null && realStatus != null && !stubStatus.equals(realStatus)) {
            collected.add(new DriftRecord()
                .setExpectationId(expectationId)
                .setDriftType(DriftType.STATUS)
                .setField("statusCode")
                .setExpectedValue(String.valueOf(stubStatus))
                .setActualValue(String.valueOf(realStatus))
                .setConfidence(1.0)
                .setEpochTimeMs(now));
        }
    }

    private void collectHeaders(List<DriftRecord> collected, String expectationId, HttpResponse stubResponse, HttpResponse realResponse, long now) {
        Map<String, String> stubHeaders = toHeaderMap(stubResponse.getHeaderList());
        Map<String, String> realHeaders = toHeaderMap(realResponse.getHeaderList());

        for (Map.Entry<String, String> entry : realHeaders.entrySet()) {
            String key = entry.getKey();
            if (SKIP_HEADERS.contains(key)) {
                continue;
            }
            if (!stubHeaders.containsKey(key)) {
                collected.add(new DriftRecord()
                    .setExpectationId(expectationId)
                    .setDriftType(DriftType.HEADER_ADDED)
                    .setField("header." + key)
                    .setActualValue(entry.getValue())
                    .setConfidence(0.9)
                    .setEpochTimeMs(now));
            } else if (!stubHeaders.get(key).equals(entry.getValue())) {
                collected.add(new DriftRecord()
                    .setExpectationId(expectationId)
                    .setDriftType(DriftType.HEADER_CHANGED)
                    .setField("header." + key)
                    .setExpectedValue(stubHeaders.get(key))
                    .setActualValue(entry.getValue())
                    .setConfidence(0.85)
                    .setEpochTimeMs(now));
            }
        }
        for (Map.Entry<String, String> entry : stubHeaders.entrySet()) {
            String key = entry.getKey();
            if (SKIP_HEADERS.contains(key)) {
                continue;
            }
            if (!realHeaders.containsKey(key)) {
                collected.add(new DriftRecord()
                    .setExpectationId(expectationId)
                    .setDriftType(DriftType.HEADER_REMOVED)
                    .setField("header." + key)
                    .setExpectedValue(entry.getValue())
                    .setConfidence(0.9)
                    .setEpochTimeMs(now));
            }
        }
    }

    private void collectJsonSchema(List<DriftRecord> collected, String expectationId, HttpResponse stubResponse, HttpResponse realResponse, long now) {
        String stubBody = stubResponse.getBodyAsString();
        String realBody = realResponse.getBodyAsString();
        if (!isJson(stubBody) || !isJson(realBody)) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            JsonNode stubNode = mapper.readTree(stubBody);
            JsonNode realNode = mapper.readTree(realBody);

            StructuralShapeDiff.ShapeDiff shapeDiff = StructuralShapeDiff.diff(stubNode, realNode);

            for (String path : shapeDiff.getAddedPaths()) {
                collected.add(new DriftRecord()
                    .setExpectationId(expectationId)
                    .setDriftType(DriftType.SCHEMA_FIELD_ADDED)
                    .setField(path)
                    .setConfidence(0.9)
                    .setEpochTimeMs(now));
            }
            for (String path : shapeDiff.getRemovedPaths()) {
                collected.add(new DriftRecord()
                    .setExpectationId(expectationId)
                    .setDriftType(DriftType.SCHEMA_FIELD_REMOVED)
                    .setField(path)
                    .setConfidence(0.95)
                    .setEpochTimeMs(now));
            }
            for (String path : shapeDiff.getTypeChangedPaths()) {
                collected.add(new DriftRecord()
                    .setExpectationId(expectationId)
                    .setDriftType(DriftType.SCHEMA_TYPE_CHANGED)
                    .setField(path)
                    .setConfidence(0.95)
                    .setEpochTimeMs(now));
            }
        } catch (Exception ignored) {
            // not valid JSON — skip schema diff
        }
    }

    /**
     * Check whether the p95 response time for the given expectation exceeds the
     * configured threshold, and if so emit a PERFORMANCE drift record.
     *
     * @param expectationId the expectation to check
     * @param responseTimeMs the response time just observed (already recorded in PercentileTracker)
     * @param now the current epoch time in milliseconds
     */
    public void checkPerformanceDrift(String expectationId, long responseTimeMs, long now) {
        long threshold = this.responseTimeThresholdMs;
        if (threshold <= 0) {
            return;
        }
        long p95 = PercentileTracker.getInstance().p95(expectationId);
        if (p95 > threshold) {
            DriftRecord record = new DriftRecord()
                .setExpectationId(expectationId)
                .setDriftType(DriftType.PERFORMANCE)
                .setField("p95_response_time_ms")
                .setExpectedValue("<=" + threshold)
                .setActualValue(String.valueOf(p95))
                .setConfidence(0.8)
                .setEpochTimeMs(now);
            store.add(record);
            DriftAlertNotifier.getInstance().onDriftStored(record);
        }
    }

    private boolean isJson(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String trimmed = body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private Map<String, String> toHeaderMap(List<Header> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Header header : headers) {
            if (header.getName() != null && header.getName().getValue() != null) {
                String key = header.getName().getValue().toLowerCase();
                String val = header.getValues() != null
                    ? header.getValues().stream()
                        .map(NottableString::getValue)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(","))
                    : "";
                map.put(key, val);
            }
        }
        return map;
    }
}
