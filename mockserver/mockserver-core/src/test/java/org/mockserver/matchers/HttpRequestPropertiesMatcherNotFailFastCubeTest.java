package org.mockserver.matchers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.Not.not;

/**
 * W3-5: NOT-operator x fail-fast equivalence cube.
 *
 * <p>The NOT-operator composition ({@code applyNotOperators} over the incoming request's
 * {@code isNot()}, the expectation request's {@code isNot()}, and the matcher's own {@code not})
 * is the load-bearing semantics of {@link HttpRequestPropertiesMatcher}. The matcher contains a
 * fail-fast (early non-match return) optimisation inside {@code failFast(...)} that short-circuits
 * as soon as a field mismatches. That optimisation MUST produce the identical final verdict as a
 * full evaluation with {@code matchersFailFast} disabled — the early return is purely a performance
 * concern and must never change the answer.</p>
 *
 * <p>This parameterized test exercises the full cube:</p>
 * <ul>
 *   <li>matcher {@code not}: true / false</li>
 *   <li>expectation-request {@code isNot()}: true / false</li>
 *   <li>incoming-request {@code isNot()}: true / false</li>
 *   <li>{@code matchersFailFast}: on / off</li>
 *   <li>which single field mismatches: none / method / path / header / body</li>
 * </ul>
 *
 * <p><strong>Why every non-first field is exercised:</strong> the fail-fast short-circuit used to
 * negate a <em>partial</em> "have we failed so far?" signal through the NOT operators. With an odd
 * number of NOT flags and zero failures accumulated <em>so far</em>, that partial signal evaluated
 * to a premature (wrong) non-match — so every field that <em>matched</em> before the first
 * mismatching field would falsely short-circuit a NOT expectation. The METHOD field is evaluated
 * first, so it escaped the bug; PATH, HEADER and BODY (each evaluated after at least one matching
 * field) exposed it. Driving the base mismatch on each of those fields, across the whole NOT cube,
 * pins that the fail-fast and full-evaluation paths now agree in every cell and both equal the NOT
 * truth table.</p>
 *
 * <p>For every cell it asserts that:</p>
 * <ol>
 *   <li>the fail-fast and non-fail-fast evaluations produce the <em>identical</em> final verdict
 *       (the fail-fast optimisation never changes the answer), and</li>
 *   <li>that verdict equals the expected NOT-composition truth table value: the base match
 *       (all-fields-match when no field mismatches) negated once per enabled NOT flag (sequential,
 *       independent negation — an odd number of NOTs flips the result).</li>
 * </ol>
 */
@RunWith(Parameterized.class)
public class HttpRequestPropertiesMatcherNotFailFastCubeTest {

    private static final String MATCH_METHOD = "GET";
    private static final String MATCH_PATH = "/test";
    private static final String MATCH_HEADER_NAME = "X-Test";
    private static final String MATCH_HEADER_VALUE = "value";
    private static final String MATCH_BODY = "expected-body";

    @Parameterized.Parameter(0)
    public boolean matcherNot;
    @Parameterized.Parameter(1)
    public boolean expectationNot;
    @Parameterized.Parameter(2)
    public boolean requestNot;
    @Parameterized.Parameter(3)
    public String mismatchField;

    @Parameterized.Parameters(name = "matcherNot={0}, expectationNot={1}, requestNot={2}, mismatch={3}")
    public static List<Object[]> data() {
        List<Object[]> params = new ArrayList<>();
        for (boolean matcherNot : new boolean[]{false, true}) {
            for (boolean expectationNot : new boolean[]{false, true}) {
                for (boolean requestNot : new boolean[]{false, true}) {
                    // "method" is evaluated first (escaped the bug); "path", "header" and "body"
                    // are each evaluated after at least one matching field, which is exactly where
                    // the fail-fast short-circuit used to diverge from full evaluation under odd NOT.
                    for (String mismatch : new String[]{"none", "method", "path", "header", "body"}) {
                        params.add(new Object[]{matcherNot, expectationNot, requestNot, mismatch});
                    }
                }
            }
        }
        return params;
    }

    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpRequestPropertiesMatcherNotFailFastCubeTest.class);

    private HttpRequest expectationRequest() {
        HttpRequest expectation = request()
            .withMethod(MATCH_METHOD)
            .withPath(MATCH_PATH)
            .withHeader(MATCH_HEADER_NAME, MATCH_HEADER_VALUE)
            .withBody(MATCH_BODY);
        return expectationNot ? not(expectation) : expectation;
    }

    private HttpRequest incomingRequest() {
        // start fully matching, then flip exactly one field to force a clean base mismatch
        HttpRequest incoming = request()
            .withMethod(MATCH_METHOD)
            .withPath(MATCH_PATH)
            .withHeader(MATCH_HEADER_NAME, MATCH_HEADER_VALUE)
            .withBody(MATCH_BODY);
        switch (mismatchField) {
            case "method":
                incoming.withMethod("POST");
                break;
            case "path":
                incoming.withPath("/other");
                break;
            case "header":
                // the expectation requires X-Test: value under SUB_SET header matching, so the
                // required header must be PRESENT in the request. Omitting it entirely is a clean
                // base non-match. (A differing *value* for a present required header does NOT
                // force a base mismatch — the header value matcher is permissive — so we drop the
                // whole header rather than change its value.)
                incoming.removeHeader(MATCH_HEADER_NAME);
                break;
            case "body":
                incoming.withBody("different-body");
                break;
            default:
                // "none" leaves the request fully matching
                break;
        }
        return requestNot ? not(incoming) : incoming;
    }

    private boolean evaluate(boolean failFast) {
        Configuration configuration = configuration().matchersFailFast(failFast);
        HttpRequestPropertiesMatcher matcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        matcher.update(new Expectation(expectationRequest()));
        if (matcherNot) {
            notMatcher(matcher);
        }
        return matcher.matches(null, incomingRequest());
    }

    /**
     * The base (pre-NOT) match: true only when no field mismatches.
     */
    private boolean baseMatches() {
        return "none".equals(mismatchField);
    }

    /**
     * Expected truth table: base match negated once per enabled NOT flag. Sequential independent
     * negation means the result flips iff an odd number of NOT flags are set.
     */
    private boolean expectedVerdict() {
        boolean result = baseMatches();
        if (matcherNot) {
            result = !result;
        }
        if (expectationNot) {
            result = !result;
        }
        if (requestNot) {
            result = !result;
        }
        return result;
    }

    @Test
    public void failFastAndNonFailFastAgreeWithNotCompositionTruthTable() {
        boolean withFailFast = evaluate(true);
        boolean withoutFailFast = evaluate(false);
        boolean expected = expectedVerdict();

        assertThat(
            "fail-fast and non-fail-fast verdicts must be identical for the same NOT composition",
            withFailFast, is(withoutFailFast)
        );
        assertThat(
            "verdict must match NOT-composition truth table (fail-fast on)",
            withFailFast, is(expected)
        );
        assertThat(
            "verdict must match NOT-composition truth table (fail-fast off)",
            withoutFailFast, is(expected)
        );
    }
}
