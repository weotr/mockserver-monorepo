package org.mockserver.graphql;

/**
 * Thrown when a GraphQL schema or query cannot be parsed, or a schema-valid response
 * cannot be synthesized for a request.
 */
public class GraphQLSchemaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GraphQLSchemaException(String message) {
        super(message);
    }

    public GraphQLSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
