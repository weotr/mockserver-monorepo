package org.mockserver.matchers;

import org.apache.commons.lang3.StringUtils;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.slf4j.event.Level.DEBUG;

/**
 * Matches an actual HTTP response status code against a template status-code expression.
 *
 * <p>The matcher supports three template forms, selected by the two constructor arguments:
 *
 * <ul>
 *   <li><b>Exact (default)</b> — when {@code statusCodeRange} is {@code null}/blank, the actual
 *       status is matched by exact equality to {@code exactStatusCode}. When {@code exactStatusCode}
 *       is also {@code null} every status matches. This is the historical (pre-range) behaviour and
 *       is byte-for-byte unchanged.</li>
 *   <li><b>Class range</b> — when {@code statusCodeRange} is a single digit followed by {@code XX}
 *       (case-insensitive, e.g. {@code "2XX"}, {@code "5xx"}), the actual status matches when it is
 *       in {@code [N00, N99]}.</li>
 *   <li><b>Numeric operator</b> — when {@code statusCodeRange} is a leading comparison operator
 *       followed by a number (e.g. {@code ">= 400"}, {@code "> 200"}, {@code "< 300"},
 *       {@code "<= 204"}, {@code "== 201"}), the actual status is compared numerically by reusing
 *       {@link NumericComparisonMatcher}.</li>
 * </ul>
 *
 * <p>An unparseable {@code statusCodeRange} is a clean non-match (logged at DEBUG); it never throws.
 *
 * @author jamesdbloom
 */
class StatusCodeMatcher {

    // one digit followed by "XX" (case-insensitive), e.g. 1XX..5XX, also accepts 2xx
    private static final Pattern CLASS_RANGE_PATTERN = Pattern.compile("^\\s*([0-9])[xX]{2}\\s*$");

    private final Integer exactStatusCode;
    private final String statusCodeRange;
    private final MockServerLogger mockServerLogger;

    StatusCodeMatcher(Integer exactStatusCode, String statusCodeRange) {
        this(exactStatusCode, statusCodeRange, null);
    }

    StatusCodeMatcher(Integer exactStatusCode, String statusCodeRange, MockServerLogger mockServerLogger) {
        this.exactStatusCode = exactStatusCode;
        this.statusCodeRange = statusCodeRange;
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * @return {@code true} when {@code actual} satisfies this matcher's template expression.
     */
    boolean matches(int actual) {
        if (StringUtils.isBlank(statusCodeRange)) {
            // exact behaviour: a null exactStatusCode matches all, otherwise exact equality
            return exactStatusCode == null || exactStatusCode == actual;
        }

        String expression = statusCodeRange.trim();

        Matcher classRange = CLASS_RANGE_PATTERN.matcher(expression);
        if (classRange.matches()) {
            int low = Integer.parseInt(classRange.group(1)) * 100;
            return actual >= low && actual <= low + 99;
        }

        if (NumericComparisonMatcher.isNumericComparison(expression)) {
            return NumericComparisonMatcher.matches(expression, Integer.toString(actual));
        }

        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(DEBUG)
                    .setMessageFormat("unparseable statusCodeRange [" + statusCodeRange + "] - treating as non-match")
            );
        }
        return false;
    }
}
