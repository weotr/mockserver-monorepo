package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.serialization.ObjectMapperFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

/**
 * Given an OpenAPI spec, generates deliberately malformed and boundary-case requests
 * for each operation, sends them via an injected HTTP-send function, and classifies
 * each outcome as HANDLED (4xx) or UNEXPECTED (5xx, 2xx, or connection error).
 * <p>
 * The class is HTTP-client-agnostic; the caller wires in the real client via the
 * {@code httpSender} function passed to {@link #runResiliencyTests}.
 */
public class OpenApiResiliencyTest {

    private final MockServerLogger mockServerLogger;
    private final OpenApiContractTest contractTest;
    private final ObjectMapper objectMapper;

    public OpenApiResiliencyTest(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        this.contractTest = new OpenApiContractTest(mockServerLogger);
        this.objectMapper = ObjectMapperFactory.createObjectMapper(new JsonNodeExampleSerializer());
    }

    /**
     * Runs resiliency tests for each operation in the spec.
     *
     * @param specUrlOrPayload  URL, file path, or inline JSON/YAML of the OpenAPI spec
     * @param baseUrl           base URL of the service under test (e.g. "http://localhost:8080")
     * @param operationIdFilter optional filter to test only a specific operation
     * @param httpSender        function that sends an HttpRequest and returns an HttpResponse
     * @return structured report with per-mutation results and summary
     */
    public ResiliencyTestReport runResiliencyTests(
        String specUrlOrPayload,
        String baseUrl,
        String operationIdFilter,
        Function<HttpRequest, HttpResponse> httpSender
    ) {
        List<MutationResult> allResults = new ArrayList<>();
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String pathTemplate = pathEntry.getKey();
            io.swagger.v3.oas.models.PathItem pathItem = pathEntry.getValue();

            for (Pair<String, Operation> methodOp : mapOperations(pathItem)) {
                String method = methodOp.getLeft();
                Operation operation = methodOp.getRight();
                String operationId = operation.getOperationId();

                if (isNotBlank(operationIdFilter) && !operationIdFilter.equals(operationId)) {
                    continue;
                }

                try {
                    HttpRequest validRequest = contractTest.buildExampleRequest(openAPI, method, pathTemplate, operation);
                    List<Mutation> mutations = generateMutations(openAPI, method, pathTemplate, operation, validRequest);

                    for (Mutation mutation : mutations) {
                        MutationResult result = executeMutation(mutation, operationId, method, pathTemplate, httpSender);
                        allResults.add(result);
                    }
                } catch (Exception e) {
                    allResults.add(new MutationResult(
                        operationId, method, pathTemplate,
                        MutationType.MALFORMED_JSON_BODY,
                        "error generating mutations: " + e.getMessage(),
                        0, Classification.UNEXPECTED
                    ));
                }
            }
        }

        return new ResiliencyTestReport(allResults);
    }

    private MutationResult executeMutation(
        Mutation mutation,
        String operationId,
        String method,
        String pathTemplate,
        Function<HttpRequest, HttpResponse> httpSender
    ) {
        try {
            HttpResponse response = httpSender.apply(mutation.request);
            int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 0;
            Classification classification = classifyResponse(statusCode);
            return new MutationResult(operationId, method, pathTemplate,
                mutation.type, mutation.description, statusCode, classification);
        } catch (Exception e) {
            return new MutationResult(operationId, method, pathTemplate,
                mutation.type, mutation.description, 0, Classification.UNEXPECTED);
        }
    }

    static Classification classifyResponse(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            return Classification.HANDLED;
        }
        return Classification.UNEXPECTED;
    }

    /**
     * Generates the bounded, explicitly-enumerated mutation catalogue for a given operation.
     */
    @SuppressWarnings("rawtypes")
    List<Mutation> generateMutations(
        OpenAPI openAPI,
        String method,
        String pathTemplate,
        Operation operation,
        HttpRequest validRequest
    ) {
        List<Mutation> mutations = new ArrayList<>();

        // 1. Omit required path parameters
        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                if ("path".equals(param.getIn()) && Boolean.TRUE.equals(param.getRequired())) {
                    mutations.add(omitPathParameter(validRequest, pathTemplate, param));
                }
            }
        }

        // 2. Omit required query parameters
        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                if ("query".equals(param.getIn()) && Boolean.TRUE.equals(param.getRequired())) {
                    mutations.add(omitQueryParameter(validRequest, param));
                }
            }
        }

        // 3. Body mutations (only when request body exists)
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
            MediaType mediaType = getJsonMediaType(requestBody);
            if (mediaType != null && mediaType.getSchema() != null) {
                Schema schema = mediaType.getSchema();
                Map<String, Schema> definitions = openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null;

                // 3a. Omit required body fields
                mutations.addAll(omitRequiredBodyFields(validRequest, schema, definitions));

                // 3b. Type violations on body fields
                mutations.addAll(typeViolations(validRequest, schema, definitions));

                // 3c. Numeric boundary violations
                mutations.addAll(numericBoundaryViolations(validRequest, schema, definitions));

                // 3d. String length boundary violations
                mutations.addAll(stringLengthBoundaryViolations(validRequest, schema, definitions));

                // 3e. Oversized string body field
                mutations.addAll(oversizedStringFields(validRequest, schema, definitions));

                // 3f. Malformed/unparseable JSON body
                mutations.add(malformedJsonBody(validRequest));
            }
        }

        return mutations;
    }

    @SuppressWarnings("rawtypes")
    private MediaType getJsonMediaType(RequestBody requestBody) {
        if (requestBody.getContent().containsKey("application/json")) {
            return requestBody.getContent().get("application/json");
        }
        // fall back to first entry
        for (Map.Entry<String, MediaType> entry : requestBody.getContent().entrySet()) {
            return entry.getValue();
        }
        return null;
    }

    private Mutation omitPathParameter(HttpRequest validRequest, String pathTemplate, Parameter param) {
        // Replace the resolved path param value with empty string, producing an invalid path
        HttpRequest mutated = cloneRequest(validRequest);
        // Path was already resolved; we need to make it malformed by removing the segment
        // Replace the segment corresponding to this path param with empty
        String paramPlaceholder = "{" + param.getName() + "}";
        // Rebuild path from template, omitting this param
        String newPath = pathTemplate.replace(paramPlaceholder, "");
        // Clean up double slashes
        newPath = newPath.replaceAll("//+", "/");
        if (newPath.endsWith("/") && newPath.length() > 1) {
            newPath = newPath.substring(0, newPath.length() - 1);
        }
        mutated.withPath(newPath);
        return new Mutation(
            MutationType.OMIT_REQUIRED_PATH_PARAM,
            "omit required path parameter '" + param.getName() + "'",
            mutated
        );
    }

    private Mutation omitQueryParameter(HttpRequest validRequest, Parameter param) {
        HttpRequest mutated = cloneRequest(validRequest);
        // Remove this specific query parameter
        if (mutated.getQueryStringParameterList() != null) {
            List<org.mockserver.model.Parameter> filtered = new ArrayList<>();
            for (org.mockserver.model.Parameter p : mutated.getQueryStringParameterList()) {
                if (p.getName() != null && !p.getName().getValue().equals(param.getName())) {
                    filtered.add(p);
                }
            }
            mutated.withQueryStringParameters(filtered);
        }
        return new Mutation(
            MutationType.OMIT_REQUIRED_QUERY_PARAM,
            "omit required query parameter '" + param.getName() + "'",
            mutated
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Mutation> omitRequiredBodyFields(HttpRequest validRequest, Schema schema, Map<String, Schema> definitions) {
        List<Mutation> mutations = new ArrayList<>();
        List<String> required = schema.getRequired();
        Map<String, Schema> properties = schema.getProperties();
        if (required != null && properties != null) {
            for (String fieldName : required) {
                try {
                    String bodyStr = validRequest.getBodyAsString();
                    if (bodyStr != null && !bodyStr.isEmpty()) {
                        JsonNode bodyJson = objectMapper.readTree(bodyStr);
                        if (bodyJson.isObject()) {
                            ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                            modified.remove(fieldName);
                            HttpRequest mutated = cloneRequest(validRequest);
                            mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                            mutations.add(new Mutation(
                                MutationType.OMIT_REQUIRED_BODY_FIELD,
                                "omit required body field '" + fieldName + "'",
                                mutated
                            ));
                        }
                    }
                } catch (Exception ignored) {
                    // skip if body cannot be parsed
                }
            }
        }
        return mutations;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Mutation> typeViolations(HttpRequest validRequest, Schema schema, Map<String, Schema> definitions) {
        List<Mutation> mutations = new ArrayList<>();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return mutations;
        }
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String fieldName = entry.getKey();
            Schema fieldSchema = entry.getValue();
            String fieldType = fieldSchema.getType();
            if (fieldType == null) {
                continue;
            }
            try {
                String bodyStr = validRequest.getBodyAsString();
                if (bodyStr != null && !bodyStr.isEmpty()) {
                    JsonNode bodyJson = objectMapper.readTree(bodyStr);
                    if (bodyJson.isObject()) {
                        ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                        // Insert a value of the wrong type
                        switch (fieldType) {
                            case "integer":
                            case "number":
                                modified.put(fieldName, "not_a_number");
                                break;
                            case "boolean":
                                modified.put(fieldName, "not_a_boolean");
                                break;
                            case "string":
                                modified.put(fieldName, 99999);
                                break;
                            case "array":
                                modified.put(fieldName, "not_an_array");
                                break;
                            case "object":
                                modified.put(fieldName, "not_an_object");
                                break;
                            default:
                                continue;
                        }
                        HttpRequest mutated = cloneRequest(validRequest);
                        mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                        mutations.add(new Mutation(
                            MutationType.TYPE_VIOLATION,
                            "type violation on field '" + fieldName + "' (expected " + fieldType + ")",
                            mutated
                        ));
                    }
                }
            } catch (Exception ignored) {
                // skip if body cannot be parsed
            }
        }
        return mutations;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Mutation> numericBoundaryViolations(HttpRequest validRequest, Schema schema, Map<String, Schema> definitions) {
        List<Mutation> mutations = new ArrayList<>();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return mutations;
        }
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String fieldName = entry.getKey();
            Schema fieldSchema = entry.getValue();
            String fieldType = fieldSchema.getType();
            if (!"integer".equals(fieldType) && !"number".equals(fieldType)) {
                continue;
            }
            try {
                String bodyStr = validRequest.getBodyAsString();
                if (bodyStr == null || bodyStr.isEmpty()) {
                    continue;
                }
                JsonNode bodyJson = objectMapper.readTree(bodyStr);
                if (!bodyJson.isObject()) {
                    continue;
                }

                BigDecimal minimum = fieldSchema.getMinimum();
                BigDecimal maximum = fieldSchema.getMaximum();

                if (minimum != null) {
                    ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                    BigDecimal belowMin = minimum.subtract(BigDecimal.ONE);
                    if ("integer".equals(fieldType)) {
                        modified.put(fieldName, belowMin.longValue());
                    } else {
                        modified.put(fieldName, belowMin.doubleValue());
                    }
                    HttpRequest mutated = cloneRequest(validRequest);
                    mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                    mutations.add(new Mutation(
                        MutationType.NUMERIC_BOUNDARY_VIOLATION,
                        "numeric boundary violation on field '" + fieldName + "' (minimum-1 = " + belowMin + ")",
                        mutated
                    ));
                }

                if (maximum != null) {
                    ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                    BigDecimal aboveMax = maximum.add(BigDecimal.ONE);
                    if ("integer".equals(fieldType)) {
                        modified.put(fieldName, aboveMax.longValue());
                    } else {
                        modified.put(fieldName, aboveMax.doubleValue());
                    }
                    HttpRequest mutated = cloneRequest(validRequest);
                    mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                    mutations.add(new Mutation(
                        MutationType.NUMERIC_BOUNDARY_VIOLATION,
                        "numeric boundary violation on field '" + fieldName + "' (maximum+1 = " + aboveMax + ")",
                        mutated
                    ));
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        return mutations;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Mutation> stringLengthBoundaryViolations(HttpRequest validRequest, Schema schema, Map<String, Schema> definitions) {
        List<Mutation> mutations = new ArrayList<>();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return mutations;
        }
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String fieldName = entry.getKey();
            Schema fieldSchema = entry.getValue();
            if (!"string".equals(fieldSchema.getType())) {
                continue;
            }
            try {
                String bodyStr = validRequest.getBodyAsString();
                if (bodyStr == null || bodyStr.isEmpty()) {
                    continue;
                }
                JsonNode bodyJson = objectMapper.readTree(bodyStr);
                if (!bodyJson.isObject()) {
                    continue;
                }

                Integer minLength = fieldSchema.getMinLength();
                Integer maxLength = fieldSchema.getMaxLength();

                if (minLength != null && minLength > 0) {
                    ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                    // Generate a string that is minLength-1 characters
                    String tooShort = repeatChar('x', minLength - 1);
                    modified.put(fieldName, tooShort);
                    HttpRequest mutated = cloneRequest(validRequest);
                    mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                    mutations.add(new Mutation(
                        MutationType.STRING_LENGTH_BOUNDARY_VIOLATION,
                        "string length violation on field '" + fieldName + "' (minLength-1 = " + (minLength - 1) + ")",
                        mutated
                    ));
                }

                if (maxLength != null) {
                    ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                    // Generate a string that is maxLength+1 characters
                    String tooLong = repeatChar('x', maxLength + 1);
                    modified.put(fieldName, tooLong);
                    HttpRequest mutated = cloneRequest(validRequest);
                    mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                    mutations.add(new Mutation(
                        MutationType.STRING_LENGTH_BOUNDARY_VIOLATION,
                        "string length violation on field '" + fieldName + "' (maxLength+1 = " + (maxLength + 1) + ")",
                        mutated
                    ));
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        return mutations;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Mutation> oversizedStringFields(HttpRequest validRequest, Schema schema, Map<String, Schema> definitions) {
        List<Mutation> mutations = new ArrayList<>();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return mutations;
        }
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String fieldName = entry.getKey();
            Schema fieldSchema = entry.getValue();
            if (!"string".equals(fieldSchema.getType())) {
                continue;
            }
            // Only generate if maxLength is not already set (those are covered by boundary violations)
            if (fieldSchema.getMaxLength() != null) {
                continue;
            }
            try {
                String bodyStr = validRequest.getBodyAsString();
                if (bodyStr == null || bodyStr.isEmpty()) {
                    continue;
                }
                JsonNode bodyJson = objectMapper.readTree(bodyStr);
                if (!bodyJson.isObject()) {
                    continue;
                }

                ObjectNode modified = ((ObjectNode) bodyJson).deepCopy();
                // Use a 10KB oversized string
                String oversized = repeatChar('A', 10_000);
                modified.put(fieldName, oversized);
                HttpRequest mutated = cloneRequest(validRequest);
                mutated.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modified));
                mutations.add(new Mutation(
                    MutationType.OVERSIZED_STRING_FIELD,
                    "oversized string for field '" + fieldName + "' (10000 chars)",
                    mutated
                ));
            } catch (Exception ignored) {
                // skip
            }
        }
        return mutations;
    }

    private Mutation malformedJsonBody(HttpRequest validRequest) {
        HttpRequest mutated = cloneRequest(validRequest);
        mutated.withBody("{this is: not valid json!!! [}");
        return new Mutation(
            MutationType.MALFORMED_JSON_BODY,
            "malformed/unparseable JSON body",
            mutated
        );
    }

    private HttpRequest cloneRequest(HttpRequest original) {
        HttpRequest clone = HttpRequest.request()
            .withMethod(original.getMethod() != null ? original.getMethod().getValue() : "GET")
            .withPath(original.getPath() != null ? original.getPath().getValue() : "/");

        if (original.getQueryStringParameterList() != null) {
            clone.withQueryStringParameters(new ArrayList<>(original.getQueryStringParameterList()));
        }
        if (original.getHeaderList() != null) {
            for (org.mockserver.model.Header header : original.getHeaderList()) {
                if (header.getName() != null && header.getValues() != null && !header.getValues().isEmpty()) {
                    String[] values = new String[header.getValues().size()];
                    for (int i = 0; i < header.getValues().size(); i++) {
                        values[i] = header.getValues().get(i).getValue();
                    }
                    clone.withHeader(header.getName().getValue(), values);
                }
            }
        }
        String body = original.getBodyAsString();
        if (body != null && !body.isEmpty()) {
            clone.withBody(body);
        }
        return clone;
    }

    private static String repeatChar(char c, int count) {
        if (count <= 0) {
            return "";
        }
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // --- Result types ---

    public enum MutationType {
        OMIT_REQUIRED_PATH_PARAM,
        OMIT_REQUIRED_QUERY_PARAM,
        OMIT_REQUIRED_BODY_FIELD,
        TYPE_VIOLATION,
        NUMERIC_BOUNDARY_VIOLATION,
        STRING_LENGTH_BOUNDARY_VIOLATION,
        OVERSIZED_STRING_FIELD,
        MALFORMED_JSON_BODY
    }

    public enum Classification {
        /** The service returned a 4xx response — it rejected the bad input cleanly. */
        HANDLED,
        /** The service returned 5xx, 2xx, or a connection error — it failed to handle the bad input. */
        UNEXPECTED
    }

    static class Mutation {
        final MutationType type;
        final String description;
        final HttpRequest request;

        Mutation(MutationType type, String description, HttpRequest request) {
            this.type = type;
            this.description = description;
            this.request = request;
        }
    }

    public static class MutationResult {
        private final String operationId;
        private final String method;
        private final String path;
        private final MutationType mutationType;
        private final String mutationDescription;
        private final int statusCode;
        private final Classification classification;

        public MutationResult(String operationId, String method, String path,
                              MutationType mutationType, String mutationDescription,
                              int statusCode, Classification classification) {
            this.operationId = operationId;
            this.method = method;
            this.path = path;
            this.mutationType = mutationType;
            this.mutationDescription = mutationDescription;
            this.statusCode = statusCode;
            this.classification = classification;
        }

        public String getOperationId() {
            return operationId;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public MutationType getMutationType() {
            return mutationType;
        }

        public String getMutationDescription() {
            return mutationDescription;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Classification getClassification() {
            return classification;
        }
    }

    public static class ResiliencyTestReport {
        private final List<MutationResult> results;

        public ResiliencyTestReport(List<MutationResult> results) {
            this.results = results;
        }

        public List<MutationResult> getResults() {
            return results;
        }

        public int getTotalMutations() {
            return results.size();
        }

        public int getHandledCount() {
            int count = 0;
            for (MutationResult r : results) {
                if (r.classification == Classification.HANDLED) {
                    count++;
                }
            }
            return count;
        }

        public int getUnexpectedCount() {
            int count = 0;
            for (MutationResult r : results) {
                if (r.classification == Classification.UNEXPECTED) {
                    count++;
                }
            }
            return count;
        }

        /** Returns per-operation summaries. */
        public Map<String, OperationSummary> getOperationSummaries() {
            Map<String, OperationSummary> summaries = new LinkedHashMap<>();
            for (MutationResult r : results) {
                String key = r.operationId != null ? r.operationId : (r.method + " " + r.path);
                OperationSummary summary = summaries.get(key);
                if (summary == null) {
                    summary = new OperationSummary(r.operationId, r.method, r.path);
                    summaries.put(key, summary);
                }
                if (r.classification == Classification.HANDLED) {
                    summary.handled++;
                } else {
                    summary.unexpected++;
                }
            }
            return summaries;
        }
    }

    public static class OperationSummary {
        private final String operationId;
        private final String method;
        private final String path;
        int handled;
        int unexpected;

        OperationSummary(String operationId, String method, String path) {
            this.operationId = operationId;
            this.method = method;
            this.path = path;
        }

        public String getOperationId() {
            return operationId;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public int getHandled() {
            return handled;
        }

        public int getUnexpected() {
            return unexpected;
        }
    }
}
