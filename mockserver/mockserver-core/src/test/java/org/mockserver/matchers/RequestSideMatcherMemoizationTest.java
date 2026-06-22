package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Cookies;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies the request-side (matched) key/value structure is converted once per request and reused across
 * the multiple matcher evaluations performed during an expectation scan, and that the memo is correctly
 * invalidated when the request collection is mutated mid-scan (e.g. query-parameter splitting).
 */
public class RequestSideMatcherMemoizationTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test
    public void shouldReuseRequestSideMultiMapConversionAcrossMatcherEvaluations() {
        // given - one request, many candidate expectations (matchers)
        Headers requestHeaders = new Headers().withEntries(
            new Header("keyOne", "valueOne"),
            new Header("keyTwo", "valueTwo")
        );
        MultiValueMapMatcher matcherOne = new MultiValueMapMatcher(mockServerLogger, new Headers().withEntries(new Header("keyOne", "valueOne")), false);
        MultiValueMapMatcher matcherTwo = new MultiValueMapMatcher(mockServerLogger, new Headers().withEntries(new Header("keyTwo", "valueTwo")), false);
        MultiValueMapMatcher matcherThree = new MultiValueMapMatcher(mockServerLogger, new Headers().withEntries(new Header("missing", "value")), false);

        // and - no conversion cached before first match
        assertThat(requestHeaders.getConvertedMatcher(false), is(nullValue()));

        // when - first evaluation
        assertThat(matcherOne.matches(null, requestHeaders), is(true));
        Object convertedAfterFirst = requestHeaders.getConvertedMatcher(false);

        // then - the conversion is now cached
        assertThat(convertedAfterFirst, is(notNullValue()));

        // when - further evaluations against the same unmutated request
        assertThat(matcherTwo.matches(null, requestHeaders), is(true));
        assertThat(matcherThree.matches(null, requestHeaders), is(false));

        // then - the exact same converted instance was reused (not rebuilt)
        assertThat(requestHeaders.getConvertedMatcher(false), is(sameInstance(convertedAfterFirst)));
    }

    @Test
    public void shouldReuseRequestSideHashMapConversionAcrossMatcherEvaluations() {
        // given
        Cookies requestCookies = new Cookies().withEntries(
            new Cookie("keyOne", "valueOne"),
            new Cookie("keyTwo", "valueTwo")
        );
        HashMapMatcher matcherOne = new HashMapMatcher(mockServerLogger, new Cookies().withEntries(new Cookie("keyOne", "valueOne")), false);
        HashMapMatcher matcherTwo = new HashMapMatcher(mockServerLogger, new Cookies().withEntries(new Cookie("keyTwo", "valueTwo")), false);

        assertThat(requestCookies.getConvertedMatcher(false), is(nullValue()));

        // when
        assertThat(matcherOne.matches(null, requestCookies), is(true));
        Object convertedAfterFirst = requestCookies.getConvertedMatcher(false);
        assertThat(convertedAfterFirst, is(notNullValue()));
        assertThat(matcherTwo.matches(null, requestCookies), is(true));

        // then
        assertThat(requestCookies.getConvertedMatcher(false), is(sameInstance(convertedAfterFirst)));
    }

    @Test
    public void shouldKeepDataPlaneAndControlPlaneConversionsSeparate() {
        // given
        Headers requestHeaders = new Headers().withEntries(new Header("keyOne", "valueOne"));
        MultiValueMapMatcher dataPlaneMatcher = new MultiValueMapMatcher(mockServerLogger, new Headers().withEntries(new Header("keyOne", "valueOne")), false);
        MultiValueMapMatcher controlPlaneMatcher = new MultiValueMapMatcher(mockServerLogger, new Headers().withEntries(new Header("keyOne", "valueOne")), true);

        // when
        assertThat(dataPlaneMatcher.matches(null, requestHeaders), is(true));
        assertThat(controlPlaneMatcher.matches(null, requestHeaders), is(true));

        // then - distinct conversions are memoized per control-plane flag and never share a slot
        Object dataPlaneConverted = requestHeaders.getConvertedMatcher(false);
        Object controlPlaneConverted = requestHeaders.getConvertedMatcher(true);
        assertThat(dataPlaneConverted, is(notNullValue()));
        assertThat(controlPlaneConverted, is(notNullValue()));
        assertThat(dataPlaneConverted == controlPlaneConverted, is(false));
    }

    @Test
    public void shouldInvalidateRequestSideConversionWhenCollectionMutated() {
        // given
        Headers requestHeaders = new Headers().withEntries(new Header("keyOne", "valueOne"));
        MultiValueMapMatcher matcher = new MultiValueMapMatcher(mockServerLogger, new Headers().withEntries(new Header("keyOne", "valueOne")), false);

        // when - first match populates the memo
        assertThat(matcher.matches(null, requestHeaders), is(true));
        assertThat(requestHeaders.getConvertedMatcher(false), is(notNullValue()));

        // and - the request collection is mutated
        requestHeaders.withEntry(new Header("keyTwo", "valueTwo"));

        // then - the stale conversion has been cleared
        assertThat(requestHeaders.getConvertedMatcher(false), is(nullValue()));
    }

    @Test
    public void shouldMatchQueryParametersCorrectlyAcrossMultipleExpectationsAfterSplit() {
        // given - a request whose multi-valued query parameter is split in place during matching, then
        //         re-matched against a second expectation; a stale memo would make the second match wrong
        Parameters requestQueryParameters = new Parameters().withEntries(
            new Parameter("status", "active,pending")
        );

        MultiValueMapMatcher expectationOne = new MultiValueMapMatcher(mockServerLogger, new Parameters().withEntries(new Parameter("status", "active,pending")), false);

        // when - first expectation matches the un-split value, populating the memo
        assertThat(expectationOne.matches(null, requestQueryParameters), is(true));
        assertThat(requestQueryParameters.getConvertedMatcher(false), is(notNullValue()));

        // and - the request query parameter is mutated in place (as ExpandedParameterDecoder.splitParameters does)
        requestQueryParameters.replaceEntry(new Parameter("status", "active", "pending"));

        // then - the memo was invalidated by the mutation
        assertThat(requestQueryParameters.getConvertedMatcher(false), is(nullValue()));

        // and - a second expectation now sees the post-split values (two separate values), not a stale memo
        MultiValueMapMatcher expectationTwoSplit = new MultiValueMapMatcher(mockServerLogger, new Parameters().withEntries(new Parameter("status", "active", "pending")), false);
        assertThat(expectationTwoSplit.matches(null, requestQueryParameters), is(true));

        // and - an expectation still expecting the original combined value no longer matches the split request
        MultiValueMapMatcher expectationExpectingCombined = new MultiValueMapMatcher(mockServerLogger, new Parameters().withEntries(new Parameter("status", "active,pending")), false);
        assertThat(expectationExpectingCombined.matches(null, requestQueryParameters), is(false));
    }
}
