package org.mockserver.mock.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a CURRENT set of recorded interactions against a previously-saved BASELINE and produces a
 * structured {@link BaselineDiffReport} so CI can detect when the shape of recorded traffic changed.
 *
 * <p>Interactions are matched across the two sets by a stable request key of {@code METHOD path}
 * (method upper-cased, path normalized by stripping a trailing slash). For each matched pair:
 * <ul>
 *     <li>request fields are diffed with {@link TrafficDiffEngine} (method, path, body, headers, query, cookies)</li>
 *     <li>response <em>structure</em> is diffed — status code, headers, and JSON body <em>shape</em></li>
 * </ul>
 *
 * <p><strong>Structural (value-insensitive) response body diffing:</strong> JSON bodies are compared by
 * shape, not by value. A different value at the same field with the same JSON type is NOT drift; a
 * new field, a removed field, or a field whose JSON type changed (e.g. string → number, object →
 * array) IS drift. Non-JSON bodies fall back to an exact-string comparison.
 */
public class BaselineDiffer {

    private final TrafficDiffEngine requestDiffEngine = new TrafficDiffEngine();
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    /**
     * One recorded interaction (request + response). Decouples the differ from whether the caller
     * supplied {@link Expectation}s or raw request/response pairs.
     */
    public static class Interaction {
        private final HttpRequest request;
        private final HttpResponse response;

        public Interaction(HttpRequest request, HttpResponse response) {
            this.request = request;
            this.response = response;
        }

        public HttpRequest getRequest() {
            return request;
        }

        public HttpResponse getResponse() {
            return response;
        }
    }

    /**
     * Compare two sets of expectations. Only expectations with a concrete {@link HttpRequest} and an
     * {@link HttpResponse} action participate in the diff.
     */
    public BaselineDiffReport diffExpectations(List<Expectation> baseline, List<Expectation> current) {
        return diff(toInteractions(baseline), toInteractions(current));
    }

    /**
     * Compare two sets of recorded interactions.
     */
    public BaselineDiffReport diff(List<Interaction> baseline, List<Interaction> current) {
        Map<String, Interaction> baselineByKey = indexByKey(baseline);
        Map<String, Interaction> currentByKey = indexByKey(current);

        BaselineDiffReport report = new BaselineDiffReport();

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(baselineByKey.keySet());
        allKeys.addAll(currentByKey.keySet());

        for (String key : allKeys) {
            Interaction baselineInteraction = baselineByKey.get(key);
            Interaction currentInteraction = currentByKey.get(key);

            if (baselineInteraction == null) {
                report.getAdded().add(InteractionDiff.of(key));
            } else if (currentInteraction == null) {
                report.getRemoved().add(InteractionDiff.of(key));
            } else {
                List<FieldDiff> requestDiffs = requestDiffEngine.diff(
                    baselineInteraction.getRequest(), currentInteraction.getRequest());
                // the matching key already normalizes method + trailing-slash path, so a diff that
                // is purely that normalization (e.g. "/x/" vs "/x") is not real drift
                requestDiffs.removeIf(this::isNormalizedPathDiff);
                List<FieldDiff> responseDiffs = diffResponseStructure(
                    baselineInteraction.getResponse(), currentInteraction.getResponse());
                if (!requestDiffs.isEmpty() || !responseDiffs.isEmpty()) {
                    report.getChanged().add(InteractionDiff.of(key)
                        .setRequestDiffs(requestDiffs)
                        .setResponseDiffs(responseDiffs));
                }
            }
        }

        return report;
    }

    private List<Interaction> toInteractions(List<Expectation> expectations) {
        List<Interaction> interactions = new ArrayList<>();
        if (expectations == null) {
            return interactions;
        }
        for (Expectation expectation : expectations) {
            if (expectation == null) {
                continue;
            }
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest) {
                interactions.add(new Interaction((HttpRequest) requestDefinition, expectation.getHttpResponse()));
            }
        }
        return interactions;
    }

    private Map<String, Interaction> indexByKey(List<Interaction> interactions) {
        Map<String, Interaction> byKey = new LinkedHashMap<>();
        if (interactions == null) {
            return byKey;
        }
        for (Interaction interaction : interactions) {
            if (interaction == null || interaction.getRequest() == null) {
                continue;
            }
            byKey.putIfAbsent(requestKey(interaction.getRequest()), interaction);
        }
        return byKey;
    }

    /**
     * Stable cross-set matching key: {@code METHOD normalized-path}. Method is upper-cased; the path
     * has a single trailing slash stripped (but {@code "/"} is preserved).
     */
    public static String requestKey(HttpRequest request) {
        String method = nottableValue(request.getMethod());
        String path = nottableValue(request.getPath());
        if (method == null || method.isEmpty()) {
            method = "GET";
        }
        return method.toUpperCase() + " " + normalizePath(path);
    }

    private boolean isNormalizedPathDiff(FieldDiff diff) {
        if (!"path".equals(diff.getField()) || diff.getDiffType() != FieldDiff.DiffType.CHANGED) {
            return false;
        }
        return normalizePath(diff.getExpectedValue()).equals(normalizePath(diff.getActualValue()));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    // ----- response structural diffing -----

    private List<FieldDiff> diffResponseStructure(HttpResponse baseline, HttpResponse current) {
        List<FieldDiff> diffs = new ArrayList<>();
        if (baseline == null && current == null) {
            return diffs;
        }
        if (baseline == null) {
            diffs.add(FieldDiff.added("response", "entire response"));
            return diffs;
        }
        if (current == null) {
            diffs.add(FieldDiff.removed("response", "entire response"));
            return diffs;
        }

        // status code
        String baselineStatus = baseline.getStatusCode() != null ? String.valueOf(baseline.getStatusCode()) : null;
        String currentStatus = current.getStatusCode() != null ? String.valueOf(current.getStatusCode()) : null;
        if (!equalsNullable(baselineStatus, currentStatus)) {
            diffs.add(FieldDiff.changed("response.statusCode", baselineStatus, currentStatus));
        }

        // headers — presence/absence and value drift (header values ARE part of response shape)
        diffHeaderNames(diffs, baseline, current);

        // body — structural (value-insensitive) JSON shape comparison
        diffBodyShape(diffs, baseline.getBodyAsString(), current.getBodyAsString());

        return diffs;
    }

    private void diffHeaderNames(List<FieldDiff> diffs, HttpResponse baseline, HttpResponse current) {
        Map<String, String> baselineHeaders = headerMap(baseline);
        Map<String, String> currentHeaders = headerMap(current);
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(baselineHeaders.keySet());
        allKeys.addAll(currentHeaders.keySet());
        for (String key : allKeys) {
            String baselineValue = baselineHeaders.get(key);
            String currentValue = currentHeaders.get(key);
            if (baselineValue == null) {
                diffs.add(FieldDiff.added("response.header." + key, currentValue));
            } else if (currentValue == null) {
                diffs.add(FieldDiff.removed("response.header." + key, baselineValue));
            } else if (!baselineValue.equals(currentValue)) {
                diffs.add(FieldDiff.changed("response.header." + key, baselineValue, currentValue));
            }
        }
    }

    private Map<String, String> headerMap(HttpResponse response) {
        Map<String, String> map = new LinkedHashMap<>();
        if (response.getHeaderList() == null) {
            return map;
        }
        response.getHeaderList().forEach(header -> {
            if (header.getName() != null) {
                String key = header.getName().getValue().toLowerCase();
                StringBuilder value = new StringBuilder();
                if (header.getValues() != null) {
                    for (int i = 0; i < header.getValues().size(); i++) {
                        if (i > 0) {
                            value.append(",");
                        }
                        value.append(header.getValues().get(i).getValue());
                    }
                }
                map.put(key, value.toString());
            }
        });
        return map;
    }

    /**
     * Compare two response bodies by JSON <em>shape</em>. When both bodies parse as JSON, only
     * added/removed fields and field-type changes are reported — a different value at the same
     * field+type is NOT drift. When either body is not JSON, falls back to exact-string comparison.
     */
    private void diffBodyShape(List<FieldDiff> diffs, String baselineBody, String currentBody) {
        boolean baselineBlank = baselineBody == null || baselineBody.isEmpty();
        boolean currentBlank = currentBody == null || currentBody.isEmpty();
        if (baselineBlank && currentBlank) {
            return;
        }
        if (baselineBlank) {
            diffs.add(FieldDiff.added("response.body", currentBody));
            return;
        }
        if (currentBlank) {
            diffs.add(FieldDiff.removed("response.body", baselineBody));
            return;
        }

        JsonNode baselineNode = tryParse(baselineBody);
        JsonNode currentNode = tryParse(currentBody);
        if (baselineNode == null || currentNode == null) {
            // not both JSON — compare exactly
            if (!baselineBody.equals(currentBody)) {
                diffs.add(FieldDiff.changed("response.body", baselineBody, currentBody));
            }
            return;
        }

        diffJsonShape(diffs, "response.body", baselineNode, currentNode);
    }

    private JsonNode tryParse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private void diffJsonShape(List<FieldDiff> diffs, String path, JsonNode baseline, JsonNode current) {
        String baselineType = nodeType(baseline);
        String currentType = nodeType(current);
        if (!baselineType.equals(currentType)) {
            diffs.add(FieldDiff.changed(path, baselineType, currentType));
            return;
        }

        if (baseline.isObject()) {
            Set<String> allFields = new LinkedHashSet<>();
            baseline.fieldNames().forEachRemaining(allFields::add);
            current.fieldNames().forEachRemaining(allFields::add);
            for (String field : allFields) {
                JsonNode baselineChild = baseline.get(field);
                JsonNode currentChild = current.get(field);
                String childPath = path + "." + field;
                if (baselineChild == null) {
                    diffs.add(FieldDiff.added(childPath, nodeType(currentChild)));
                } else if (currentChild == null) {
                    diffs.add(FieldDiff.removed(childPath, nodeType(baselineChild)));
                } else {
                    diffJsonShape(diffs, childPath, baselineChild, currentChild);
                }
            }
        } else if (baseline.isArray()) {
            // compare element shapes positionally; arrays of differing length only drift on
            // the structurally-different (added/removed) elements
            int max = Math.max(baseline.size(), current.size());
            for (int i = 0; i < max; i++) {
                String childPath = path + "[" + i + "]";
                JsonNode baselineChild = i < baseline.size() ? baseline.get(i) : null;
                JsonNode currentChild = i < current.size() ? current.get(i) : null;
                if (baselineChild == null) {
                    diffs.add(FieldDiff.added(childPath, nodeType(currentChild)));
                } else if (currentChild == null) {
                    diffs.add(FieldDiff.removed(childPath, nodeType(baselineChild)));
                } else {
                    diffJsonShape(diffs, childPath, baselineChild, currentChild);
                }
            }
        }
        // scalars of the same type: value difference is NOT structural drift — ignore
    }

    /**
     * JSON shape category — values within a category are interchangeable (not drift). All numeric
     * kinds collapse to {@code "number"} so 1 vs 1.5 vs 100 are the same shape.
     */
    private String nodeType(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "string";
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nottableValue(NottableString nottableString) {
        return nottableString != null ? nottableString.getValue() : null;
    }
}
