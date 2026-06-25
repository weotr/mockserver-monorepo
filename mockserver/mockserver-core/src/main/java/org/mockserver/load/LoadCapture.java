package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * Declarative cross-step capture / correlation rule for a {@link LoadStep}. After the step's
 * response returns, each capture extracts a value from that response and binds it to {@link #name}
 * in the iteration's mutable captured-variable map, so a SUBSEQUENT step in the SAME iteration can
 * reference it from its templated request fields (path, body and headers) via
 * {@code $iteration.captured.<name>} (Velocity) / {@code {{iteration.captured.<name>}}} (Mustache).
 *
 * <p>Scope is <b>per-iteration</b> — one virtual user's single pass through the steps (the natural
 * "one user session" correlation scope). The map is created fresh at the start of each iteration and
 * is never shared across virtual users or across a VU's successive iterations, so it is race-free and
 * captures never leak between users or runs.
 *
 * <p>The primary use case is a login step capturing a token that later steps replay, e.g.
 * {@code Authorization: Bearer {{iteration.captured.token}}}.
 *
 * <ul>
 *   <li>{@link #getName()} — the variable name later steps reference</li>
 *   <li>{@link #getSource()} — where to extract from (response body JSONPath, header, or body regex)</li>
 *   <li>{@link #getExpression()} — the JSONPath, header name, or regex (group 1 for regex)</li>
 *   <li>{@link #getDefaultValue()} — used when extraction yields nothing (otherwise the var is left unset)</li>
 * </ul>
 */
public class LoadCapture extends ObjectWithJsonToString {

    /**
     * Where in the step's response a {@link LoadCapture} extracts its value from.
     */
    public enum Source {
        /** Evaluate {@link #getExpression()} as a JSONPath over the response body (string/JSON). */
        BODY_JSONPATH,
        /** Take the first value of the response header named by {@link #getExpression()}. */
        HEADER,
        /** Match {@link #getExpression()} as a regex over the response body string; capture group 1. */
        BODY_REGEX
    }

    private String name;
    private Source source;
    private String expression;
    private String defaultValue;

    public static LoadCapture loadCapture() {
        return new LoadCapture();
    }

    public static LoadCapture loadCapture(String name, Source source, String expression) {
        return new LoadCapture().withName(name).withSource(source).withExpression(expression);
    }

    /**
     * The variable name later steps reference (may be null/blank, in which case the capture is skipped).
     */
    public String getName() {
        return name;
    }

    public LoadCapture withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * The extraction source (may be null, in which case the capture is skipped).
     */
    public Source getSource() {
        return source;
    }

    public LoadCapture withSource(Source source) {
        this.source = source;
        return this;
    }

    /**
     * The JSONPath, header name, or regex driving extraction (may be null, in which case the capture is skipped).
     */
    public String getExpression() {
        return expression;
    }

    public LoadCapture withExpression(String expression) {
        this.expression = expression;
        return this;
    }

    /**
     * The fallback value bound to {@link #name} when extraction yields nothing (may be null, in which
     * case the variable is left unset on no match).
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    public LoadCapture withDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }
}
