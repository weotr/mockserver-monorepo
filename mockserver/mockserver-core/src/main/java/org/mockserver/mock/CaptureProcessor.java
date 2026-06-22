package org.mockserver.mock;

import com.jayway.jsonpath.JsonPath;
import org.mockserver.model.CaptureRule;
import org.mockserver.model.Cookie;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.xml.XPathEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.xpath.XPathConstants;
import java.util.Collection;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Declarative capture-rule processor (WS2.2). When an expectation matches an
 * incoming request, {@link #process(List, RequestDefinition)} evaluates each
 * {@link CaptureRule} against the request and stores the extracted value into
 * scenario state, so a later request's response template can read it back via
 * {@code scenario.get(name)} (WS2.1).
 * <p>
 * The state is written to the <b>live</b> {@link ScenarioManager} resolved from
 * {@link CrossProtocolEventBus#getInstance()} &mdash; the same instance the
 * {@code scenario} template helper reads from &mdash; so a value captured here
 * is immediately visible to a subsequent template read and to matcher
 * {@code matchesState} checks.
 * <p>
 * Capture is best-effort and side-effect-only: a malformed expression, a missing
 * value, or the absence of a live {@link ScenarioManager} is logged at debug and
 * skipped, never failing the matched response. An expectation without capture
 * rules is entirely unaffected.
 */
public class CaptureProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(CaptureProcessor.class);

    private CaptureProcessor() {
    }

    /**
     * Evaluates each capture rule against the request and writes the captured
     * value into scenario state. No-op when {@code captureRules} is null/empty,
     * the request is not an {@link HttpRequest}, or no live {@link ScenarioManager}
     * is available.
     */
    public static void process(List<CaptureRule> captureRules, RequestDefinition requestDefinition) {
        if (captureRules == null || captureRules.isEmpty() || !(requestDefinition instanceof HttpRequest)) {
            return;
        }
        ScenarioManager scenarioManager = CrossProtocolEventBus.getInstance().getScenarioManager();
        if (scenarioManager == null) {
            return;
        }
        HttpRequest request = (HttpRequest) requestDefinition;
        for (CaptureRule rule : captureRules) {
            try {
                if (rule == null || rule.getSource() == null || isBlank(rule.getInto())) {
                    continue;
                }
                String value = extract(rule, request);
                if (value != null) {
                    scenarioManager.setState(rule.getInto(), value);
                }
            } catch (Throwable throwable) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("capture rule {} failed - skipping: {}", rule, throwable.getMessage());
                }
            }
        }
    }

    private static String extract(CaptureRule rule, HttpRequest request) {
        String expression = rule.getExpression();
        switch (rule.getSource()) {
            case jsonPath:
                return extractJsonPath(expression, request);
            case xpath:
                return extractXPath(expression, request);
            case header:
                return blankToNull(request.getFirstHeader(expression));
            case queryStringParameter:
                return blankToNull(request.getFirstQueryStringParameter(expression));
            case pathParameter:
                return blankToNull(request.getFirstPathParameter(expression));
            case cookie:
                return blankToNull(extractCookie(expression, request));
            default:
                return null;
        }
    }

    private static String extractJsonPath(String expression, HttpRequest request) {
        if (isBlank(expression)) {
            return null;
        }
        String body = request.getBodyAsJsonOrXmlString();
        if (isBlank(body)) {
            return null;
        }
        Object result = JsonPath.compile(expression).read(body);
        return stringify(result);
    }

    private static String extractXPath(String expression, HttpRequest request) {
        if (isBlank(expression)) {
            return null;
        }
        String body = request.getBodyAsJsonOrXmlString();
        if (isBlank(body)) {
            return null;
        }
        XPathEvaluator evaluator = new XPathEvaluator(expression, null);
        Object result = evaluator.evaluateXPathExpression(body, (matched, throwable, level) -> {
            // best-effort: swallow parse/evaluation diagnostics, handled by the caller's try/catch
        }, XPathConstants.STRING);
        String value = result != null ? result.toString() : null;
        return isBlank(value) ? null : value;
    }

    private static String extractCookie(String name, HttpRequest request) {
        if (isBlank(name) || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies().getEntries()) {
            if (cookie.getName() != null && name.equals(cookie.getName().getValue())) {
                return cookie.getValue() != null ? cookie.getValue().getValue() : null;
            }
        }
        return null;
    }

    /**
     * Renders a JSONPath result as a plain string. A scalar (number/string/boolean)
     * becomes its {@code toString()}; a single-element collection (the common
     * "definite path returning a list" case under some JsonPath configurations)
     * is unwrapped to its element; anything else is rendered with {@code toString()}.
     */
    private static String stringify(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof Collection) {
            Collection<?> collection = (Collection<?>) result;
            if (collection.isEmpty()) {
                return null;
            }
            if (collection.size() == 1) {
                Object only = collection.iterator().next();
                return only != null ? String.valueOf(only) : null;
            }
        }
        return String.valueOf(result);
    }

    private static boolean isBlank(String value) {
        return !isNotBlank(value);
    }

    /**
     * Treats a blank extraction result as "value not present" so a missing header /
     * query / path / cookie (which {@link HttpRequest} reports as {@code ""}) does not
     * overwrite scenario state with an empty string.
     */
    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
