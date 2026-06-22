package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

@SuppressWarnings("rawtypes")
public class OpenAPIRequestValidator {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    public static List<String> validate(String specUrlOrPayload, HttpRequest request, MockServerLogger logger) {
        List<String> errors = new ArrayList<>();
        try {
            OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, logger);
            String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
            String requestMethod = request.getMethod() != null ? request.getMethod().getValue().toLowerCase() : "get";

            Optional<Operation> matchedOperation = findMatchingOperation(openAPI, requestPath, requestMethod);
            if (!matchedOperation.isPresent()) {
                errors.add("no operation found matching " + requestMethod.toUpperCase() + " " + requestPath + " in OpenAPI spec");
                return errors;
            }

            Operation operation = matchedOperation.get();
            validateRequestBody(operation, request, logger, errors);
        } catch (Throwable throwable) {
            errors.add(OpenAPIValidationErrors.unexpectedError("OpenAPI request validation", throwable, logger));
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Operation> findMatchingOperation(OpenAPI openAPI, String requestPath, String requestMethod) {
        // Search paths first. A valid OAS 3.1 document may omit paths entirely, so getPaths() can be
        // null — treat it as empty rather than NPE-ing. Among the path templates that match the
        // request, prefer the most specific (fewest "{...}" placeholders) so a concrete path such as
        // "/users/me" wins over a templated "/users/{id}", per the OpenAPI path-precedence rules.
        Optional<Operation> result = Optional.empty();
        if (openAPI.getPaths() != null) {
            result = openAPI
                .getPaths()
                .entrySet()
                .stream()
                .filter(entry -> pathMatches(entry.getKey(), requestPath))
                .sorted(Comparator.comparingInt((Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry) -> placeholderCount(entry.getKey())))
                .flatMap(entry -> mapOperations(entry.getValue()).stream())
                .filter(pair -> pair.getLeft().equalsIgnoreCase(requestMethod))
                .map(Pair::getRight)
                .findFirst();
        }
        // Then search webhooks (OAS 3.1) — use webhook name as pseudo-path
        if (result.isEmpty() && openAPI.getWebhooks() != null) {
            result = openAPI.getWebhooks()
                .entrySet()
                .stream()
                .filter(entry -> pathMatches("/webhook:" + entry.getKey(), requestPath))
                .flatMap(entry -> mapOperations(entry.getValue()).stream())
                .filter(pair -> pair.getLeft().equalsIgnoreCase(requestMethod))
                .map(Pair::getRight)
                .findFirst();
        }
        return result;
    }

    /**
     * Counts the number of {@code {...}} placeholder segments in a path template. Used to rank
     * matching templates so the most specific (fewest placeholders) is selected first — i.e. a
     * concrete path beats a templated one per OpenAPI path-precedence rules.
     */
    static int placeholderCount(String templatePath) {
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

    @SuppressWarnings("unchecked")
    private static void validateRequestBody(Operation operation, HttpRequest request, MockServerLogger logger, List<String> errors) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null) {
            return;
        }

        String bodyString = request.getBodyAsString();
        if (isBlank(bodyString)) {
            if (requestBody.getRequired() != null && requestBody.getRequired()) {
                errors.add("request body is required but was empty");
            }
            return;
        }

        if (requestBody.getContent() == null || requestBody.getContent().isEmpty()) {
            return;
        }

        String contentType = request.getFirstHeader("content-type");
        MediaType mediaType = null;
        if (isNotBlank(contentType)) {
            String baseContentType = contentType.contains(";") ? contentType.substring(0, contentType.indexOf(";")).trim() : contentType.trim();
            mediaType = requestBody.getContent().get(baseContentType);
        }
        if (mediaType == null) {
            mediaType = requestBody.getContent().get("application/json");
        }
        if (mediaType == null) {
            mediaType = requestBody.getContent().get("*/*");
        }
        if (mediaType == null && !requestBody.getContent().isEmpty()) {
            mediaType = requestBody.getContent().values().iterator().next();
        }

        if (mediaType == null) {
            return;
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
                errors.add("request body validation error: " + validationResult);
            }
        } catch (Throwable throwable) {
            errors.add(OpenAPIValidationErrors.unexpectedError("validating request body against schema", throwable, logger));
        }
    }
}
