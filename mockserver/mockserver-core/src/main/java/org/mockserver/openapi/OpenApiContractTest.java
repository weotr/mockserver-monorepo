package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectWriter;
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
import org.mockserver.openapi.examples.ExampleBuilder;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.StringExample;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.*;
import java.util.function.Function;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

/**
 * Given an OpenAPI spec, builds representative example requests for each operation,
 * sends them via an injected HTTP-send function, and validates each response against the spec.
 * <p>
 * The class is HTTP-client-agnostic; the caller wires in the real client via the
 * {@code httpSender} function passed to {@link #runContractTests}.
 */
public class OpenApiContractTest {

    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(new JsonNodeExampleSerializer()).writerWithDefaultPrettyPrinter();
    private final MockServerLogger mockServerLogger;

    public OpenApiContractTest(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Runs contract tests for each operation in the spec.
     *
     * @param specUrlOrPayload URL, file path, or inline JSON/YAML of the OpenAPI spec
     * @param baseUrl          base URL of the service under test (e.g. "http://localhost:8080")
     * @param operationIdFilter optional filter to test only a specific operation
     * @param httpSender       function that sends an HttpRequest and returns an HttpResponse
     * @return list of per-operation results
     */
    public List<ContractTestResult> runContractTests(
        String specUrlOrPayload,
        String baseUrl,
        String operationIdFilter,
        Function<HttpRequest, HttpResponse> httpSender
    ) {
        List<ContractTestResult> results = new ArrayList<>();
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // Iterate over all paths and operations
        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String pathTemplate = pathEntry.getKey();
            io.swagger.v3.oas.models.PathItem pathItem = pathEntry.getValue();

            for (Pair<String, Operation> methodOp : mapOperations(pathItem)) {
                String method = methodOp.getLeft();
                Operation operation = methodOp.getRight();
                String operationId = operation.getOperationId();

                // Apply filter
                if (isNotBlank(operationIdFilter) && !operationIdFilter.equals(operationId)) {
                    continue;
                }

                try {
                    HttpRequest exampleRequest = buildExampleRequest(openAPI, method, pathTemplate, operation);
                    HttpResponse response = httpSender.apply(exampleRequest);

                    // Validate the response
                    List<String> responseErrors = OpenAPIResponseValidator.validate(
                        specUrlOrPayload, operationId, response, mockServerLogger
                    );

                    int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 0;
                    boolean passed = responseErrors.isEmpty();

                    results.add(new ContractTestResult(
                        operationId, method, pathTemplate, exampleRequest,
                        statusCode, passed, responseErrors
                    ));
                } catch (Exception e) {
                    results.add(new ContractTestResult(
                        operationId, method, pathTemplate, null,
                        0, false,
                        Collections.singletonList("contract test error: " + e.getMessage())
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Builds an example HttpRequest for the given operation, including path parameters,
     * query parameters, required headers, and a request body.
     */
    HttpRequest buildExampleRequest(OpenAPI openAPI, String method, String pathTemplate, Operation operation) {
        // Resolve path parameters
        String resolvedPath = resolvePath(pathTemplate, operation, openAPI);

        HttpRequest httpRequest = request()
            .withMethod(method)
            .withPath(resolvedPath);

        // Add query parameters
        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                if ("query".equals(param.getIn())) {
                    String exampleValue = getParameterExampleValue(param, openAPI);
                    if (exampleValue != null) {
                        httpRequest.withQueryStringParameter(param.getName(), exampleValue);
                    }
                } else if ("header".equals(param.getIn())) {
                    String exampleValue = getParameterExampleValue(param, openAPI);
                    if (exampleValue != null) {
                        httpRequest.withHeader(param.getName(), exampleValue);
                    }
                }
            }
        }

        // Add request body
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
            // Prefer application/json
            Map.Entry<String, MediaType> contentEntry = null;
            if (requestBody.getContent().containsKey("application/json")) {
                contentEntry = new AbstractMap.SimpleEntry<>("application/json", requestBody.getContent().get("application/json"));
            } else {
                contentEntry = requestBody.getContent().entrySet().iterator().next();
            }

            httpRequest.withHeader("content-type", contentEntry.getKey());
            MediaType mediaType = contentEntry.getValue();
            if (mediaType != null && mediaType.getSchema() != null) {
                String bodyString = generateExampleBody(mediaType, openAPI);
                if (bodyString != null) {
                    httpRequest.withBody(bodyString);
                }
            }
        }

        return httpRequest;
    }

    private String resolvePath(String pathTemplate, Operation operation, OpenAPI openAPI) {
        String resolved = pathTemplate;
        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                if ("path".equals(param.getIn())) {
                    String exampleValue = getParameterExampleValue(param, openAPI);
                    if (exampleValue == null) {
                        exampleValue = "example";
                    }
                    resolved = resolved.replace("{" + param.getName() + "}", exampleValue);
                }
            }
        }
        // Handle any unresolved path parameters
        resolved = resolved.replaceAll("\\{[^}]+}", "example");
        return resolved;
    }

    @SuppressWarnings("rawtypes")
    private String getParameterExampleValue(Parameter param, OpenAPI openAPI) {
        // 1. Check explicit example on the parameter
        if (param.getExample() != null) {
            return String.valueOf(param.getExample());
        }
        // 2. Check examples map
        if (param.getExamples() != null && !param.getExamples().isEmpty()) {
            io.swagger.v3.oas.models.examples.Example example = param.getExamples().values().iterator().next();
            if (example != null && example.getValue() != null) {
                return String.valueOf(example.getValue());
            }
        }
        // 3. Generate from schema
        if (param.getSchema() != null) {
            Schema schema = param.getSchema();
            // Check schema default
            if (schema.getDefault() != null) {
                return String.valueOf(schema.getDefault());
            }
            // Check schema enum
            if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                return String.valueOf(schema.getEnum().get(0));
            }
            // Generate from type
            Example generatedExample = ExampleBuilder.fromSchema(
                schema,
                openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null
            );
            if (generatedExample instanceof StringExample) {
                return ((StringExample) generatedExample).getValue();
            } else if (generatedExample != null) {
                return serialise(generatedExample);
            }
        }
        // 4. Only return a value for required parameters
        if (param.getRequired() != null && param.getRequired()) {
            return "example";
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private String generateExampleBody(MediaType mediaType, OpenAPI openAPI) {
        // 1. Check inline example
        if (mediaType.getExample() != null) {
            return serialise(mediaType.getExample());
        }
        // 2. Check examples map
        if (mediaType.getExamples() != null && !mediaType.getExamples().isEmpty()) {
            io.swagger.v3.oas.models.examples.Example example = mediaType.getExamples().values().iterator().next();
            if (example != null && example.getValue() != null) {
                return serialise(example.getValue());
            }
        }
        // 3. Generate from schema
        Schema schema = mediaType.getSchema();
        if (schema != null) {
            // Check schema example
            if (schema.getExample() != null) {
                return serialise(schema.getExample());
            }
            // Generate from ExampleBuilder
            Map<String, Schema> definitions = openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null;
            Example generatedExample = ExampleBuilder.fromSchema(schema, definitions);
            if (generatedExample != null) {
                return serialise(generatedExample);
            }
        }
        return null;
    }

    private String serialise(Object example) {
        try {
            return OBJECT_WRITER.writeValueAsString(example);
        } catch (Throwable throwable) {
            return String.valueOf(example);
        }
    }

    /**
     * Structured result for a single contract test operation.
     */
    public static class ContractTestResult {
        private final String operationId;
        private final String method;
        private final String path;
        private final HttpRequest requestSent;
        private final int statusCodeReceived;
        private final boolean passed;
        private final List<String> validationErrors;

        public ContractTestResult(String operationId, String method, String path, HttpRequest requestSent,
                                  int statusCodeReceived, boolean passed, List<String> validationErrors) {
            this.operationId = operationId;
            this.method = method;
            this.path = path;
            this.requestSent = requestSent;
            this.statusCodeReceived = statusCodeReceived;
            this.passed = passed;
            this.validationErrors = validationErrors;
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

        public HttpRequest getRequestSent() {
            return requestSent;
        }

        public int getStatusCodeReceived() {
            return statusCodeReceived;
        }

        public boolean isPassed() {
            return passed;
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }
    }
}
