package org.mockserver.matchers;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches a single header / cookie / query-string parameter value by numeric
 * comparison when the expected (matcher) value is expressed as a leading
 * comparison operator followed by a number, for example:
 *
 * <pre>
 *   &gt; 60     greater than 60
 *   &gt;= 60    greater than or equal to 60
 *   &lt; 100    less than 100
 *   &lt;= 30    less than or equal to 30
 *   == 5     equal to 5
 * </pre>
 *
 * <p>To express <em>not equal</em>, negate equality with the standard
 * {@link org.mockserver.model.NottableString} {@code !} prefix: {@code !== 5}
 * means "not equal to 5". A dedicated {@code !=} operator is intentionally not
 * offered because a leading {@code !} is consumed by {@code NottableString} as
 * its negation marker before the value ever reaches this matcher.
 *
 * <p>Whitespace between the operator and the number is optional ({@code >60} and
 * {@code > 60} are equivalent). The number may be an integer or a decimal and
 * may be negative (e.g. {@code >= -1.5}).
 *
 * <p>This matcher is intentionally conservative: only when the matcher value
 * parses as one of the operator forms above does numeric comparison apply.
 * Anything else (a plain value such as {@code 5}, or a regex such as
 * {@code [0-9]+}) is not a numeric operator and the caller falls back to the
 * existing exact / regex string matching unchanged.
 *
 * <p>Negation of the overall result is handled by the surrounding
 * {@link NottableString} mechanism (the {@code !} prefix), not here.
 *
 * @author jamesdbloom
 */
class NumericComparisonMatcher {

    // operator (longest first so >= / <= / == win over > / <), optional whitespace, then the number
    // note: there is deliberately no "!=" operator — negation is via the NottableString "!" prefix (e.g. "!== 5")
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("^\\s*(>=|<=|==|>|<)\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    private NumericComparisonMatcher() {
    }

    /**
     * @return {@code true} if the matcher value is a numeric-operator expression
     * (so the caller must use {@link #matches(String, String)} rather than the
     * default string / regex behaviour).
     */
    static boolean isNumericComparison(String matcherValue) {
        return matcherValue != null && OPERATOR_PATTERN.matcher(matcherValue).matches();
    }

    /**
     * Evaluate a numeric-operator matcher value against an actual value.
     *
     * @param matcherValue an operator expression such as {@code "> 60"};
     *                      callers should first confirm {@link #isNumericComparison(String)}
     * @param matchedValue  the actual header / cookie / parameter value
     * @return {@code true} only when the actual value parses as a number and
     * satisfies the comparison; a non-numeric or blank actual value never
     * matches (and never throws)
     */
    static boolean matches(String matcherValue, String matchedValue) {
        if (matcherValue == null || StringUtils.isBlank(matchedValue)) {
            return false;
        }
        Matcher operator = OPERATOR_PATTERN.matcher(matcherValue);
        if (!operator.matches()) {
            return false;
        }
        final double expected;
        final double actual;
        try {
            expected = Double.parseDouble(operator.group(2));
            actual = Double.parseDouble(matchedValue.trim());
        } catch (NumberFormatException nfe) {
            // actual value is not numeric — simply does not match
            return false;
        }
        switch (operator.group(1)) {
            case ">":
                return actual > expected;
            case ">=":
                return actual >= expected;
            case "<":
                return actual < expected;
            case "<=":
                return actual <= expected;
            case "==":
                return actual == expected;
            default:
                return false;
        }
    }
}
