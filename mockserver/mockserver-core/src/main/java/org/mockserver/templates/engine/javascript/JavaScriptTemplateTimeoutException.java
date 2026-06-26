package org.mockserver.templates.engine.javascript;

/**
 * Thrown when a JavaScript response template is cancelled because it ran longer than the configured
 * {@code mockserver.javascriptTemplateExecutionTimeout}. Surfaced (wrapped) by
 * {@link JavaScriptTemplateEngine} so a runaway or malicious template fails fast with a clear error
 * instead of pinning a data-plane worker thread indefinitely.
 */
public class JavaScriptTemplateTimeoutException extends RuntimeException {

    public JavaScriptTemplateTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
