package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for {@link ResponseMode#WEIGHTED} probabilistic response selection.
 * <p>
 * These tests use large iteration counts with generous tolerance bands so that the assertions
 * are statistically robust against the inherent randomness without being flaky. Because the
 * selection uses {@code ThreadLocalRandom}, the iteration counts are sized so the probability
 * of a false failure is negligible.
 */
public class WeightedResponseSelectionTest {

    private static final int ITERATIONS = 100_000;

    private Map<Integer, Integer> tallyByStatusCode(Expectation expectation, int iterations) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < iterations; i++) {
            HttpResponse selected = (HttpResponse) expectation.getAction();
            counts.merge(selected.getStatusCode(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    public void shouldSelectWeightedResponsesApproximatelyByWeight() {
        // given - 90% weight on 200, 10% weight on 500
        Expectation expectation = new Expectation(request().withPath("/weighted"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(90, 10));

        // when
        Map<Integer, Integer> counts = tallyByStatusCode(expectation, ITERATIONS);

        // then - 200 should be roughly 90%, 500 roughly 10% (allow +/- 3% absolute)
        double ratio200 = counts.getOrDefault(200, 0) / (double) ITERATIONS;
        double ratio500 = counts.getOrDefault(500, 0) / (double) ITERATIONS;
        assertThat(ratio200, is(both(greaterThan(0.87)).and(lessThan(0.93))));
        assertThat(ratio500, is(both(greaterThan(0.07)).and(lessThan(0.13))));
    }

    @Test
    public void shouldSelectAcrossThreeWeightedResponses() {
        // given - weights 70 / 20 / 10
        Expectation expectation = new Expectation(request().withPath("/weighted3"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(404),
                response().withStatusCode(503)
            ))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(70, 20, 10));

        // when
        Map<Integer, Integer> counts = tallyByStatusCode(expectation, ITERATIONS);

        // then
        assertThat(counts.getOrDefault(200, 0) / (double) ITERATIONS, is(both(greaterThan(0.66)).and(lessThan(0.74))));
        assertThat(counts.getOrDefault(404, 0) / (double) ITERATIONS, is(both(greaterThan(0.17)).and(lessThan(0.23))));
        assertThat(counts.getOrDefault(503, 0) / (double) ITERATIONS, is(both(greaterThan(0.07)).and(lessThan(0.13))));
    }

    @Test
    public void shouldDefaultMissingWeightsToOneAndBehaveUniformly() {
        // given - WEIGHTED mode but no weights supplied => every response defaults to weight 1 (uniform)
        Expectation expectation = new Expectation(request().withPath("/uniform"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(201),
                response().withStatusCode(202),
                response().withStatusCode(203)
            ))
            .withResponseMode(ResponseMode.WEIGHTED);

        // when
        Map<Integer, Integer> counts = tallyByStatusCode(expectation, ITERATIONS);

        // then - each of the 4 responses ~25%
        for (int statusCode : new int[]{200, 201, 202, 203}) {
            double ratio = counts.getOrDefault(statusCode, 0) / (double) ITERATIONS;
            assertThat("status " + statusCode + " ratio=" + ratio, ratio, is(both(greaterThan(0.22)).and(lessThan(0.28))));
        }
    }

    @Test
    public void shouldTreatPartialWeightsListAsDefaultOneForMissingEntries() {
        // given - only the first response has an explicit weight; the second defaults to 1
        Expectation expectation = new Expectation(request().withPath("/partial"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(9)); // second weight missing => defaults to 1 => 90/10 split

        // when
        Map<Integer, Integer> counts = tallyByStatusCode(expectation, ITERATIONS);

        // then
        assertThat(counts.getOrDefault(200, 0) / (double) ITERATIONS, is(both(greaterThan(0.87)).and(lessThan(0.93))));
        assertThat(counts.getOrDefault(500, 0) / (double) ITERATIONS, is(both(greaterThan(0.07)).and(lessThan(0.13))));
    }

    @Test
    public void shouldTreatNonPositiveWeightAsOne() {
        // given - a zero and a negative weight are both floored to 1; 1 / 1 / 8 => 10/10/80
        Expectation expectation = new Expectation(request().withPath("/nonpositive"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(404),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(0, -5, 8));

        // when
        Map<Integer, Integer> counts = tallyByStatusCode(expectation, ITERATIONS);

        // then - 200 and 404 each ~10%, 500 ~80%
        assertThat(counts.getOrDefault(200, 0) / (double) ITERATIONS, is(both(greaterThan(0.07)).and(lessThan(0.13))));
        assertThat(counts.getOrDefault(404, 0) / (double) ITERATIONS, is(both(greaterThan(0.07)).and(lessThan(0.13))));
        assertThat(counts.getOrDefault(500, 0) / (double) ITERATIONS, is(both(greaterThan(0.77)).and(lessThan(0.83))));
    }

    @Test
    public void shouldAlwaysSelectTheOnlyNonZeroWeightedResponse() {
        // given - heavily skewed: weight 1000 vs 1, the dominant response should win the vast majority
        Expectation expectation = new Expectation(request().withPath("/skewed"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(1000, 1));

        // when
        Map<Integer, Integer> counts = tallyByStatusCode(expectation, ITERATIONS);

        // then - 200 hugely dominant, 500 rare but selection is still valid
        assertThat(counts.getOrDefault(200, 0) / (double) ITERATIONS, is(greaterThan(0.99)));
        assertThat(counts.getOrDefault(200, 0) + counts.getOrDefault(500, 0), is(ITERATIONS));
    }

    @Test
    public void shouldNotAffectRandomOrSequentialModes() {
        // given - weights present but mode is SEQUENTIAL => weights are ignored, round-robin order preserved
        Expectation expectation = new Expectation(request().withPath("/sequential"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.SEQUENTIAL)
            .withResponseWeights(Arrays.asList(90, 10));

        // when - simulate matches incrementing the counter
        expectation.consumeMatch();
        HttpResponse first = (HttpResponse) expectation.getAction();
        expectation.consumeMatch();
        HttpResponse second = (HttpResponse) expectation.getAction();

        // then - strict round-robin regardless of weights
        assertThat(first.getStatusCode(), is(200));
        assertThat(second.getStatusCode(), is(500));
    }

    @Test
    public void shouldRoundTripWeightsThroughGetters() {
        // given
        Expectation expectation = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(response().withStatusCode(200), response().withStatusCode(500)))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(90, 10));

        // then
        assertThat(expectation.getResponseMode(), is(ResponseMode.WEIGHTED));
        assertThat(expectation.getResponseWeights(), is(Arrays.asList(90, 10)));
        assertThat(expectation.getResponseWeights().size(), is(greaterThanOrEqualTo(0)));
    }
}
