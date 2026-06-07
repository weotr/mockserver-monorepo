package org.mockserver.async.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates JSON payloads against an AsyncAPI channel's JSON Schema definition,
 * reusing {@link JsonSchemaValidator} from mockserver-core (which uses the
 * {@code com.networknt:json-schema-validator} library under the hood).
 * <p>
 * Used in two contexts:
 * <ol>
 *   <li>Validating generated example messages before publishing</li>
 *   <li>Validating consumed/recorded messages from broker subscriptions</li>
 * </ol>
 */
public class AsyncApiSchemaValidator {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiSchemaValidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockServerLogger mockServerLogger;

    public AsyncApiSchemaValidator() {
        this.mockServerLogger = new MockServerLogger(AsyncApiSchemaValidator.class);
    }

    /**
     * Constructor accepting an external logger instance, consistent with
     * the codebase pattern of injecting or per-instance loggers.
     */
    public AsyncApiSchemaValidator(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Validate that the given JSON payload conforms to the given JSON Schema.
     *
     * @param payload the JSON payload string to validate
     * @param schema  the JSON Schema node from the AsyncAPI spec
     * @return a validation result; use {@link ValidationResult#isValid()} to check
     */
    public ValidationResult validate(String payload, JsonNode schema) {
        if (schema == null) {
            return ValidationResult.valid();
        }
        if (payload == null || payload.isBlank()) {
            return ValidationResult.invalid("payload is null or blank");
        }
        try {
            String schemaString = MAPPER.writeValueAsString(schema);
            JsonSchemaValidator validator = new JsonSchemaValidator(mockServerLogger, schemaString);
            String errors = validator.isValid(payload, false);
            if (errors.isEmpty()) {
                return ValidationResult.valid();
            } else {
                LOG.debug("Schema validation failed: {}", errors);
                return ValidationResult.invalid(errors);
            }
        } catch (Exception e) {
            LOG.warn("Schema validation error: {}", e.getMessage());
            return ValidationResult.invalid("schema validation error: " + e.getMessage());
        }
    }

    /**
     * Result of a schema validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errors;

        private ValidationResult(boolean valid, String errors) {
            this.valid = valid;
            this.errors = errors;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String errors) {
            return new ValidationResult(false, errors);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrors() {
            return errors;
        }
    }
}
