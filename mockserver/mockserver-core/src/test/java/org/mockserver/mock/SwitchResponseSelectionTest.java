package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for {@link ResponseMode#SWITCH} lightweight per-expectation hit-count branching.
 * <p>
 * With {@code switchAfter = N}, an expectation serves its first response for the first {@code N} matches,
 * then advances one index in {@code httpResponses} for every further block of {@code N} matches, clamping
 * at the last response. This lets a single expectation "respond differently after the Nth call" without a
 * full scenario.
 */
public class SwitchResponseSelectionTest {

    private Expectation switchingExpectation(int switchAfter, HttpResponse... responses) {
        return new Expectation(request().withPath("/switch"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(responses))
            .withResponseMode(ResponseMode.SWITCH)
            .withSwitchAfter(switchAfter);
    }

    /**
     * Simulates {@code count} live matches and returns the per-match selected status codes, mirroring the
     * runtime path where {@link Expectation#consumeMatch()} is invoked once per matched request before the
     * primary action is resolved.
     */
    private List<Integer> statusCodesOverMatches(Expectation expectation, int count) {
        List<Integer> statusCodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expectation.consumeMatch();
            statusCodes.add(((HttpResponse) expectation.getAction()).getStatusCode());
        }
        return statusCodes;
    }

    @Test
    public void shouldServeFirstResponseForFirstNCallsThenSecondResponse() {
        // given - first 3 calls -> 200, calls 4+ -> 503
        Expectation expectation = switchingExpectation(3,
            response().withStatusCode(200),
            response().withStatusCode(503)
        );

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 6);

        // then
        assertThat(statusCodes, contains(200, 200, 200, 503, 503, 503));
    }

    @Test
    public void shouldSwitchAfterFirstCallWhenThresholdIsOne() {
        // given - the canonical "respond differently after the 1st call" case
        Expectation expectation = switchingExpectation(1,
            response().withStatusCode(201),
            response().withStatusCode(409)
        );

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 4);

        // then - first call 201, every subsequent call 409
        assertThat(statusCodes, contains(201, 409, 409, 409));
    }

    @Test
    public void shouldAdvanceThroughMoreThanTwoResponsesInBlocks() {
        // given - 3 responses, switch every 2 matches: [A,A,B,B,C,C,C,C...]
        Expectation expectation = switchingExpectation(2,
            response().withStatusCode(200),
            response().withStatusCode(429),
            response().withStatusCode(500)
        );

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 7);

        // then - last response clamps for every further match
        assertThat(statusCodes, contains(200, 200, 429, 429, 500, 500, 500));
    }

    @Test
    public void shouldDefaultThresholdToOneWhenSwitchAfterUnset() {
        // given - SWITCH mode but no explicit switchAfter => threshold defaults to 1 (advance each match)
        Expectation expectation = new Expectation(request().withPath("/switch"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(404)
            ))
            .withResponseMode(ResponseMode.SWITCH);

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 3);

        // then
        assertThat(statusCodes, contains(200, 404, 404));
    }

    @Test
    public void shouldClampToLastResponseForLargeThresholdNeverReached() {
        // given - switch threshold larger than the number of calls made => always first response
        Expectation expectation = switchingExpectation(100,
            response().withStatusCode(200),
            response().withStatusCode(503)
        );

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 5);

        // then - all within the first block
        assertThat(statusCodes, contains(200, 200, 200, 200, 200));
    }

    @Test
    public void shouldNotAffectSequentialModeWhenSwitchAfterPresent() {
        // given - switchAfter present but mode is SEQUENTIAL => switchAfter ignored, round-robin preserved
        Expectation expectation = new Expectation(request().withPath("/seq"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.SEQUENTIAL)
            .withSwitchAfter(3);

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 4);

        // then - strict round-robin, switchAfter has no effect outside SWITCH mode
        assertThat(statusCodes, contains(200, 500, 200, 500));
    }

    @Test
    public void shouldLeaveSingleHttpResponseExpectationUnaffected() {
        // given - a plain single-response expectation with no SWITCH config behaves exactly as before
        Expectation expectation = new Expectation(request().withPath("/plain"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(response().withStatusCode(200));

        // when
        List<Integer> statusCodes = statusCodesOverMatches(expectation, 3);

        // then
        assertThat(statusCodes, contains(200, 200, 200));
        assertThat(expectation.getSwitchAfter(), is(nullValue()));
        assertThat(expectation.getResponseMode(), is(nullValue()));
    }

    @Test
    public void shouldRejectNonPositiveSwitchAfter() {
        // given
        Expectation expectation = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0);

        // then
        assertThrows(IllegalArgumentException.class, () -> expectation.withSwitchAfter(0));
        assertThrows(IllegalArgumentException.class, () -> expectation.withSwitchAfter(-1));
    }

    @Test
    public void shouldRoundTripSwitchAfterAndModeThroughGettersAndClone() {
        // given
        Expectation expectation = switchingExpectation(5,
            response().withStatusCode(200),
            response().withStatusCode(503)
        );

        // then - getters
        assertThat(expectation.getResponseMode(), is(ResponseMode.SWITCH));
        assertThat(expectation.getSwitchAfter(), is(5));

        // and - clone preserves the configuration
        Expectation clone = expectation.clone();
        assertThat(clone.getResponseMode(), is(ResponseMode.SWITCH));
        assertThat(clone.getSwitchAfter(), is(5));
    }
}
