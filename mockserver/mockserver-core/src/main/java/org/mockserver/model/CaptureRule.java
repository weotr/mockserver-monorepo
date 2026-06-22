package org.mockserver.model;

/**
 * Declarative capture rule (WS2.2): when an expectation matches an incoming
 * request, each capture rule extracts a value from the request and stores it
 * into scenario state under the {@link #into} name, so a later request's
 * response template can read it back via {@code scenario.get(name)} (WS2.1).
 * <p>
 * A rule has three parts:
 * <ul>
 *   <li>{@link #source} &mdash; where in the request to look ({@code jsonPath},
 *       {@code xpath}, {@code header}, {@code queryStringParameter},
 *       {@code cookie}, {@code pathParameter}).</li>
 *   <li>{@link #expression} &mdash; the selector evaluated against that source
 *       (a JSONPath / XPath expression, or a header / parameter / cookie name).</li>
 *   <li>{@link #into} &mdash; the scenario name whose state is set to the
 *       captured value.</li>
 * </ul>
 * This enables realistic auth&rarr;resource&rarr;confirm journeys without manual
 * scenario-trigger calls.
 */
public class CaptureRule extends ObjectWithReflectiveEqualsHashCodeToString {

    /**
     * The part of the request a capture rule reads from.
     */
    public enum Source {
        jsonPath,
        xpath,
        header,
        queryStringParameter,
        cookie,
        pathParameter
    }

    private Source source;
    private String expression;
    private String into;

    public static CaptureRule captureRule() {
        return new CaptureRule();
    }

    public static CaptureRule capture(Source source, String expression, String into) {
        return new CaptureRule()
            .withSource(source)
            .withExpression(expression)
            .withInto(into);
    }

    public Source getSource() {
        return source;
    }

    public CaptureRule withSource(Source source) {
        this.source = source;
        return this;
    }

    public String getExpression() {
        return expression;
    }

    public CaptureRule withExpression(String expression) {
        this.expression = expression;
        return this;
    }

    public String getInto() {
        return into;
    }

    public CaptureRule withInto(String into) {
        this.into = into;
        return this;
    }
}
