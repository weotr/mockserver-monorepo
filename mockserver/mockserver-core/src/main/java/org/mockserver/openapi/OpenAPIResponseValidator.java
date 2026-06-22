package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

@SuppressWarnings("rawtypes")
public class OpenAPIResponseValidator {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    public static List<String> validate(String specUrlOrPayload, String operationId, HttpResponse response, MockServerLogger logger) {
        List<String> errors = new ArrayList<>();
        try {
            OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, logger);
            // Search paths first, then webhooks (OAS 3.1). A valid OAS 3.1 document may omit paths
            // entirely (webhooks-only / components-only), so getPaths() can be null — treat it as an
            // empty stream rather than NPE-ing.
            java.util.stream.Stream<Pair<String, Operation>> pathOps = java.util.stream.Stream.empty();
            if (openAPI.getPaths() != null) {
                pathOps = openAPI
                    .getPaths()
                    .values()
                    .stream()
                    .flatMap(pathItem -> mapOperations(pathItem).stream());
            }
            java.util.stream.Stream<Pair<String, Operation>> webhookOps = java.util.stream.Stream.empty();
            if (openAPI.getWebhooks() != null) {
                webhookOps = openAPI.getWebhooks()
                    .values()
                    .stream()
                    .flatMap(pathItem -> mapOperations(pathItem).stream());
            }
            Optional<Pair<String, Operation>> operationPair = java.util.stream.Stream.concat(pathOps, webhookOps)
                .filter(pair -> pair.getRight().getOperationId().equals(operationId))
                .findFirst();

            if (!operationPair.isPresent()) {
                errors.add("operation " + operationId + " not found in OpenAPI spec");
                return errors;
            }

            Operation operation = operationPair.get().getRight();
            String statusCode = String.valueOf(response.getStatusCode() != null ? response.getStatusCode() : 200);
            ApiResponse apiResponse = null;

            if (operation.getResponses() != null) {
                // exact three-digit match wins
                apiResponse = operation.getResponses().get(statusCode);
                if (apiResponse == null) {
                    // then the range bucket (e.g. "2XX" for "200"). swagger-parser stores range keys
                    // verbatim, and the generator (OpenAPIConverter.parseResponseStatusCode) also
                    // accepts lowercase "2xx", so match the bucket key case-insensitively to keep the
                    // generator and validator in agreement.
                    String rangeBucket = (Character.toUpperCase(statusCode.charAt(0)) + "XX");
                    apiResponse = findResponseIgnoreCase(operation.getResponses(), rangeBucket);
                }
                if (apiResponse == null) {
                    // finally fall back to the "default" response
                    apiResponse = operation.getResponses().get("default");
                }
            }

            if (apiResponse == null) {
                String availableStatuses = operation.getResponses() != null && !operation.getResponses().isEmpty()
                    ? String.join(", ", operation.getResponses().keySet())
                    : "none";
                errors.add("response status code " + statusCode + " not defined in OpenAPI spec for operation " + operationId + " - defined response status codes are " + availableStatuses);
                return errors;
            }

            validateResponseBody(apiResponse, operationId, response, logger, errors);
            validateResponseHeaders(apiResponse, operationId, response, logger, errors);
        } catch (Throwable throwable) {
            errors.add(OpenAPIValidationErrors.unexpectedError("OpenAPI response validation for operation " + operationId, throwable, logger));
        }
        return errors;
    }

    /**
     * Looks up a response by key ignoring case. Used for the range-bucket lookup (e.g. {@code "2XX"})
     * so a spec authored with a lowercase range key such as {@code "2xx"} — which the generator
     * accepts — still matches here.
     */
    private static ApiResponse findResponseIgnoreCase(io.swagger.v3.oas.models.responses.ApiResponses responses, String bucket) {
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(bucket)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void validateResponseBody(ApiResponse apiResponse, String operationId, HttpResponse response, MockServerLogger logger, List<String> errors) {
        if (apiResponse.getContent() == null || apiResponse.getContent().isEmpty()) {
            return;
        }

        String bodyString = response.getBodyAsString();
        if (isBlank(bodyString)) {
            return;
        }

        String contentType = response.getFirstHeader("content-type");
        MediaType mediaType = null;
        if (isNotBlank(contentType)) {
            String baseContentType = contentType.contains(";") ? contentType.substring(0, contentType.indexOf(";")).trim() : contentType.trim();
            mediaType = apiResponse.getContent().get(baseContentType);
        }
        if (mediaType == null) {
            mediaType = apiResponse.getContent().get("application/json");
        }
        if (mediaType == null) {
            mediaType = apiResponse.getContent().get("*/*");
        }
        if (mediaType == null) {
            Map.Entry<String, MediaType> firstEntry = apiResponse.getContent().entrySet().iterator().next();
            mediaType = firstEntry.getValue();
        }

        Schema schema = mediaType.getSchema();
        if (schema == null) {
            return;
        }

        try {
            String schemaJson = OBJECT_MAPPER.writeValueAsString(schema);
            JsonSchemaValidator validator = JsonSchemaValidator.cachedJsonSchemaValidator(logger, schemaJson);
            String validationResult = validator.isValid(bodyString, false);
            if (isNotBlank(validationResult)) {
                errors.add("response body validation error: " + validationResult);
            }
        } catch (Throwable throwable) {
            errors.add(OpenAPIValidationErrors.unexpectedError("validating response body against schema for operation " + operationId, throwable, logger));
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateResponseHeaders(ApiResponse apiResponse, String operationId, HttpResponse response, MockServerLogger logger, List<String> errors) {
        if (apiResponse.getHeaders() == null || apiResponse.getHeaders().isEmpty()) {
            return;
        }

        for (Map.Entry<String, Header> headerEntry : apiResponse.getHeaders().entrySet()) {
            String headerName = headerEntry.getKey();
            Header headerDef = headerEntry.getValue();

            String headerValue = response.getFirstHeader(headerName);
            if (isBlank(headerValue)) {
                if (headerDef.getRequired() != null && headerDef.getRequired()) {
                    errors.add("required response header " + headerName + " not found in response");
                }
                continue;
            }

            Schema headerSchema = headerDef.getSchema();
            if (headerSchema == null) {
                continue;
            }

            try {
                String schemaJson = OBJECT_MAPPER.writeValueAsString(headerSchema);
                JsonSchemaValidator validator = JsonSchemaValidator.cachedJsonSchemaValidator(logger, schemaJson);
                // Resolve type from getType() (OAS 3.0) or getTypes() (OAS 3.1)
                String schemaType = headerSchema.getType();
                if (schemaType == null && headerSchema.getTypes() != null) {
                    @SuppressWarnings("unchecked")
                    Set<String> types = headerSchema.getTypes();
                    schemaType = types.stream()
                        .filter(t -> !"null".equals(t))
                        .findFirst()
                        .orElse(null);
                }
                String jsonValue = headerValue;
                if ("string".equals(schemaType)) {
                    jsonValue = OBJECT_MAPPER.writeValueAsString(headerValue);
                } else if ("integer".equals(schemaType) || "number".equals(schemaType)) {
                    // leave as-is, numbers are valid JSON
                } else if ("boolean".equals(schemaType)) {
                    // leave as-is, booleans are valid JSON
                } else {
                    jsonValue = OBJECT_MAPPER.writeValueAsString(headerValue);
                }
                String validationResult = validator.isValid(jsonValue, false);
                if (isNotBlank(validationResult)) {
                    errors.add("response header " + headerName + " validation error: " + validationResult);
                }
            } catch (Throwable throwable) {
                errors.add(OpenAPIValidationErrors.unexpectedError("validating response header " + headerName + " against schema for operation " + operationId, throwable, logger));
            }
        }
    }

}
