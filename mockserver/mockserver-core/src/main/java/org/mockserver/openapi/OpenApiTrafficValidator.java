package org.mockserver.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.*;

import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

/**
 * Validates a list of request/response pairs against an OpenAPI specification.
 * For each pair: locates the matching spec operation, validates the request with
 * {@link OpenAPIRequestValidator}, validates the response with {@link OpenAPIResponseValidator},
 * and produces a structured result.
 */
public class OpenApiTrafficValidator {

    private final MockServerLogger mockServerLogger;

    public OpenApiTrafficValidator(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Validates a list of request/response pairs against the given OpenAPI spec.
     *
     * @param specUrlOrPayload URL, file path, or inline JSON/YAML of the OpenAPI spec
     * @param requestResponsePairs list of request/response pairs to validate
     * @return list of validation results, one per pair
     */
    public List<TrafficValidationResult> validate(String specUrlOrPayload, List<Pair<HttpRequest, HttpResponse>> requestResponsePairs) {
        List<TrafficValidationResult> results = new ArrayList<>();
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        for (Pair<HttpRequest, HttpResponse> pair : requestResponsePairs) {
            HttpRequest request = pair.getLeft();
            HttpResponse response = pair.getRight();
            // Guard each pair so one unexpected throwable records a failed result for that pair and the
            // batch continues, rather than aborting and losing every other pair's result (parity with
            // OpenApiContractTest's per-operation guard).
            try {
                results.add(validatePair(specUrlOrPayload, openAPI, request, response));
            } catch (Throwable throwable) {
                String requestPath = request != null && request.getPath() != null ? request.getPath().getValue() : "/";
                String requestMethod = request != null && request.getMethod() != null ? request.getMethod().getValue().toUpperCase() : "GET";
                results.add(new TrafficValidationResult(
                    requestMethod, requestPath, null,
                    Collections.singletonList(OpenAPIValidationErrors.unexpectedError(
                        "OpenAPI traffic validation for " + requestMethod + " " + requestPath, throwable, mockServerLogger)),
                    Collections.emptyList(),
                    false
                ));
            }
        }
        return results;
    }

    private TrafficValidationResult validatePair(String specUrlOrPayload, OpenAPI openAPI, HttpRequest request, HttpResponse response) {
        String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
        String requestMethod = request.getMethod() != null ? request.getMethod().getValue().toUpperCase() : "GET";

        // Find matching operation
        Optional<Pair<String, Pair<String, Operation>>> matchedOperation = findMatchingOperation(openAPI, requestPath, requestMethod);

        if (!matchedOperation.isPresent()) {
            return new TrafficValidationResult(
                requestMethod, requestPath, null,
                Collections.singletonList("no matching operation found for " + requestMethod + " " + requestPath),
                Collections.emptyList(),
                false
            );
        }

        Pair<String, Pair<String, Operation>> match = matchedOperation.get();
        String operationId = match.getRight().getRight().getOperationId();
        String matchedPath = match.getLeft();
        String matchedMethodAndPath = match.getRight().getLeft().toUpperCase() + " " + matchedPath;

        // Validate request — thread the already-resolved matched path template through so path
        // parameter presence/schema validation maps the concrete path onto the right template
        List<String> requestErrors = OpenAPIRequestValidator.validate(specUrlOrPayload, request, mockServerLogger, matchedPath);

        // Validate response
        List<String> responseErrors = OpenAPIResponseValidator.validate(specUrlOrPayload, operationId, response, mockServerLogger);

        boolean passed = requestErrors.isEmpty() && responseErrors.isEmpty();

        return new TrafficValidationResult(
            requestMethod, requestPath, matchedMethodAndPath,
            requestErrors, responseErrors, passed
        );
    }

    /**
     * Find the matching operation, returning the spec path template, HTTP method, and Operation.
     */
    @SuppressWarnings("unchecked")
    private Optional<Pair<String, Pair<String, Operation>>> findMatchingOperation(OpenAPI openAPI, String requestPath, String requestMethod) {
        // A valid OAS 3.1 document may omit paths entirely, so getPaths() can be null — treat it as
        // empty rather than NPE-ing.
        if (openAPI.getPaths() == null) {
            return Optional.empty();
        }
        // Among the path templates that match the request, prefer the most specific (fewest "{...}"
        // placeholders) so a concrete path such as "/users/me" wins over a templated "/users/{id}",
        // per the OpenAPI path-precedence rules; ties keep declaration order.
        List<Map.Entry<String, io.swagger.v3.oas.models.PathItem>> matchingEntries = new ArrayList<>();
        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry : openAPI.getPaths().entrySet()) {
            if (pathMatches(entry.getKey(), requestPath)) {
                matchingEntries.add(entry);
            }
        }
        matchingEntries.sort(Comparator.comparingInt((Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry) -> placeholderCount(entry.getKey())));
        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry : matchingEntries) {
            String templatePath = entry.getKey();
            for (Pair<String, Operation> methodOp : mapOperations(entry.getValue())) {
                if (methodOp.getLeft().equalsIgnoreCase(requestMethod)) {
                    return Optional.of(Pair.of(templatePath, methodOp));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Counts the number of {@code {...}} placeholder segments in a path template. Used to rank
     * matching templates so the most specific (fewest placeholders) is selected first — i.e. a
     * concrete path beats a templated one per OpenAPI path-precedence rules.
     */
    private static int placeholderCount(String templatePath) {
        int count = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(templatePath);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean pathMatches(String templatePath, String actualPath) {
        StringBuilder regex = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(templatePath);
        int lastEnd = 0;
        while (matcher.find()) {
            regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd, matcher.start())));
            regex.append("[^/]+");
            lastEnd = matcher.end();
        }
        regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd)));
        return actualPath.matches(regex.toString());
    }

    /**
     * Structured result for a single request/response pair validation.
     */
    public static class TrafficValidationResult {
        private final String requestMethod;
        private final String requestPath;
        private final String matchedOperation;
        private final List<String> requestErrors;
        private final List<String> responseErrors;
        private final boolean passed;

        public TrafficValidationResult(String requestMethod, String requestPath, String matchedOperation,
                                       List<String> requestErrors, List<String> responseErrors, boolean passed) {
            this.requestMethod = requestMethod;
            this.requestPath = requestPath;
            this.matchedOperation = matchedOperation;
            this.requestErrors = requestErrors;
            this.responseErrors = responseErrors;
            this.passed = passed;
        }

        public String getRequestMethod() {
            return requestMethod;
        }

        public String getRequestPath() {
            return requestPath;
        }

        public String getMatchedOperation() {
            return matchedOperation;
        }

        public List<String> getRequestErrors() {
            return requestErrors;
        }

        public List<String> getResponseErrors() {
            return responseErrors;
        }

        public boolean isPassed() {
            return passed;
        }
    }
}
