package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.logging.MockServerLogger;

/**
 * Deterministic fuzzy / similarity body matcher.
 * <p>
 * Matches when the request body is at least {@code threshold} similar to the
 * expected value, where similarity is the normalised Jaro-Winkler ratio in the
 * range {@code [0.0, 1.0]} computed by {@link StringSimilarity}. Unlike the
 * LLM-based semantic matcher this is fully deterministic and requires no
 * external service.
 *
 * @author jamesdbloom
 */
public class FuzzyMatcher extends BodyMatcher<String> {

    private static final String[] EXCLUDED_FIELDS = {"mockServerLogger"};
    private final MockServerLogger mockServerLogger;
    private final String matcher;
    private final double threshold;
    private final boolean ignoreCase;

    FuzzyMatcher(MockServerLogger mockServerLogger, String matcher, double threshold, boolean ignoreCase) {
        this.mockServerLogger = mockServerLogger;
        this.matcher = matcher;
        this.threshold = threshold;
        this.ignoreCase = ignoreCase;
    }

    @Override
    public boolean matches(final MatchDifference context, String matched) {
        boolean result = false;

        if (StringUtils.isBlank(matcher)) {
            result = true;
        } else if (matched != null) {
            double similarity = StringSimilarity.jaroWinkler(normalise(matcher), normalise(matched));
            result = similarity >= threshold;
            if (!result && context != null) {
                context.addDifference(mockServerLogger, "fuzzy string match failed similarity:{}below threshold:{}expected:{}found:{}", similarity, threshold, this.matcher, matched);
            }
        }

        return not != result;
    }

    private String normalise(String value) {
        if (value == null) {
            return null;
        }
        return ignoreCase ? value.trim().toLowerCase() : value;
    }

    @Override
    public boolean isBlank() {
        return matcher == null || StringUtils.isBlank(matcher);
    }

    @Override
    @JsonIgnore
    public String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
