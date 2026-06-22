package org.mockserver.server.initialize;

/**
 * Thrown when an expectation initializer (initialization JSON / OpenAPI file or initialization class)
 * fails to load and {@code failOnInitializationError} is enabled, so that the failure aborts server
 * startup instead of being logged at WARN and ignored.
 *
 * @author jamesdbloom
 */
public class ExpectationInitializerException extends RuntimeException {

    public ExpectationInitializerException(String message, Throwable cause) {
        super(message, cause);
    }
}
