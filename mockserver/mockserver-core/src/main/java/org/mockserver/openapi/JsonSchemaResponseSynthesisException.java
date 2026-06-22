package org.mockserver.openapi;

/**
 * Thrown when a schema-valid response body cannot be generated from an inline JSON Schema
 * (for example the schema cannot be parsed, or the example engine produces no example).
 */
public class JsonSchemaResponseSynthesisException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonSchemaResponseSynthesisException(String message) {
        super(message);
    }

    public JsonSchemaResponseSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
