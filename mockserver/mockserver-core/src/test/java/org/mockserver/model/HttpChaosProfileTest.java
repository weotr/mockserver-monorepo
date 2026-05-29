package org.mockserver.model;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpChaosProfileTest {

    // --- validation tests ---

    @Test
    public void withErrorProbabilityAcceptsNull() {
        httpChaosProfile().withErrorProbability(null);
    }

    @Test
    public void withErrorProbabilityAcceptsValidRange() {
        httpChaosProfile().withErrorProbability(0.0);
        httpChaosProfile().withErrorProbability(0.5);
        httpChaosProfile().withErrorProbability(1.0);
    }

    @Test
    public void withErrorProbabilityRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorProbability(-0.1));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withErrorProbabilityRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorProbability(1.1));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withErrorProbabilityRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorProbability(Double.NaN));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got NaN"));
    }

    @Test
    public void withErrorStatusAcceptsNull() {
        httpChaosProfile().withErrorStatus(null);
    }

    @Test
    public void withErrorStatusAcceptsValidRange() {
        httpChaosProfile().withErrorStatus(100);
        httpChaosProfile().withErrorStatus(503);
        httpChaosProfile().withErrorStatus(599);
    }

    @Test
    public void withErrorStatusRejectsBelowOneHundred() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorStatus(99));
        assertThat(exception.getMessage(), is("errorStatus must be between 100 and 599, got 99"));
    }

    @Test
    public void withErrorStatusRejectsAboveFiveNineNine() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorStatus(600));
        assertThat(exception.getMessage(), is("errorStatus must be between 100 and 599, got 600"));
    }

    // --- equals/hashCode tests ---

    @Test
    public void equalsIsReflexive() {
        HttpChaosProfile profile = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.5)
            .withRetryAfter("30")
            .withLatency(Delay.milliseconds(100))
            .withSeed(42L);
        assertThat(profile, is(equalTo(profile)));
    }

    @Test
    public void equalProfilesAreEqual() {
        HttpChaosProfile a = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.5)
            .withRetryAfter("30")
            .withLatency(Delay.milliseconds(100))
            .withSeed(42L);
        HttpChaosProfile b = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.5)
            .withRetryAfter("30")
            .withLatency(Delay.milliseconds(100))
            .withSeed(42L);
        assertThat(a, is(equalTo(b)));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    @Test
    public void differentProfilesAreNotEqual() {
        HttpChaosProfile a = httpChaosProfile().withErrorStatus(503);
        HttpChaosProfile b = httpChaosProfile().withErrorStatus(500);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void latencyIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withLatency(Delay.milliseconds(100));
        HttpChaosProfile b = httpChaosProfile().withLatency(Delay.milliseconds(200));
        assertThat(a, is(not(equalTo(b))));
    }

    // --- serialization round-trip test ---

    @Test
    public void expectationWithChaosRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30")
                .withSeed(42L));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getErrorStatus(), is(503));
        assertThat(deserialized[0].getChaos().getErrorProbability(), is(1.0));
        assertThat(deserialized[0].getChaos().getRetryAfter(), is("30"));
        assertThat(deserialized[0].getChaos().getSeed(), is(42L));
    }

    @Test
    public void expectationWithChaosLatencyRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withLatency(Delay.milliseconds(500)));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getLatency().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(deserialized[0].getChaos().getLatency().getValue(), is(500L));
    }

    @Test
    public void expectationWithoutChaosDeserializesWithNullChaos() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos() == null, is(true));
    }
}
