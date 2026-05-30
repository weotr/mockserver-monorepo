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

    // --- dropConnectionProbability validation tests ---

    @Test
    public void withDropConnectionProbabilityAcceptsNull() {
        httpChaosProfile().withDropConnectionProbability(null);
    }

    @Test
    public void withDropConnectionProbabilityAcceptsValidRange() {
        httpChaosProfile().withDropConnectionProbability(0.0);
        httpChaosProfile().withDropConnectionProbability(0.5);
        httpChaosProfile().withDropConnectionProbability(1.0);
    }

    @Test
    public void withDropConnectionProbabilityRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withDropConnectionProbability(-0.1));
        assertThat(exception.getMessage(), is("dropConnectionProbability must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withDropConnectionProbabilityRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withDropConnectionProbability(1.1));
        assertThat(exception.getMessage(), is("dropConnectionProbability must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withDropConnectionProbabilityRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withDropConnectionProbability(Double.NaN));
        assertThat(exception.getMessage(), is("dropConnectionProbability must be between 0.0 and 1.0, got NaN"));
    }

    // --- truncateBodyAtFraction validation tests ---

    @Test
    public void withTruncateBodyAtFractionAcceptsNullAndRange() {
        httpChaosProfile().withTruncateBodyAtFraction(null);
        httpChaosProfile().withTruncateBodyAtFraction(0.0);
        httpChaosProfile().withTruncateBodyAtFraction(0.5);
        httpChaosProfile().withTruncateBodyAtFraction(1.0);
    }

    @Test
    public void withTruncateBodyAtFractionRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withTruncateBodyAtFraction(-0.1));
        assertThat(exception.getMessage(), is("truncateBodyAtFraction must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withTruncateBodyAtFractionRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withTruncateBodyAtFraction(1.1));
        assertThat(exception.getMessage(), is("truncateBodyAtFraction must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withTruncateBodyAtFractionRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withTruncateBodyAtFraction(Double.NaN));
        assertThat(exception.getMessage(), is("truncateBodyAtFraction must be between 0.0 and 1.0, got NaN"));
    }

    // --- slowResponseChunkSize validation tests ---

    @Test
    public void withSlowResponseChunkSizeAcceptsNullAndPositive() {
        httpChaosProfile().withSlowResponseChunkSize(null);
        httpChaosProfile().withSlowResponseChunkSize(1);
        httpChaosProfile().withSlowResponseChunkSize(1024);
    }

    @Test
    public void withSlowResponseChunkSizeRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withSlowResponseChunkSize(0));
        assertThat(exception.getMessage(), is("slowResponseChunkSize must be >= 1, got 0"));
    }

    // --- quota validation tests ---

    @Test
    public void withQuotaLimitRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withQuotaLimit(0));
        assertThat(exception.getMessage(), is("quotaLimit must be >= 1, got 0"));
    }

    @Test
    public void withQuotaWindowMillisRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withQuotaWindowMillis(0L));
        assertThat(exception.getMessage(), is("quotaWindowMillis must be >= 1, got 0"));
    }

    @Test
    public void withQuotaErrorStatusRejectsOutOfRange() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withQuotaErrorStatus(42));
        assertThat(exception.getMessage(), is("quotaErrorStatus must be between 100 and 599, got 42"));
    }

    @Test
    public void withQuotaFieldsAcceptValidValues() {
        httpChaosProfile()
            .withQuotaName("acct")
            .withQuotaLimit(1)
            .withQuotaWindowMillis(1L)
            .withQuotaErrorStatus(429);
    }

    // --- degradation ramp tests ---

    @Test
    public void withDegradationRampMillisRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withDegradationRampMillis(0L));
        assertThat(exception.getMessage(), is("degradationRampMillis must be >= 1, got 0"));
    }

    @Test
    public void degradationFactorIsOneWhenNoRampConfigured() {
        // no degradationRampMillis -> always full strength
        assertThat(httpChaosProfile().degradationFactor(1000L, 5000L), is(1.0));
    }

    @Test
    public void degradationFactorRampsLinearlyFromZeroToOne() {
        HttpChaosProfile profile = httpChaosProfile().withDegradationRampMillis(10_000L);
        // first match at t=1000
        assertThat("at the first match the factor is 0", profile.degradationFactor(1000L, 1000L), is(0.0));
        assertThat("half way through the ramp the factor is 0.5", profile.degradationFactor(1000L, 6000L), is(0.5));
        assertThat("at the end of the ramp the factor is 1.0", profile.degradationFactor(1000L, 11_000L), is(1.0));
        assertThat("beyond the ramp the factor stays at 1.0", profile.degradationFactor(1000L, 50_000L), is(1.0));
    }

    @Test
    public void degradationFactorIsOneWhenFirstMatchUnknown() {
        // firstMatch <= 0 is degenerate -> do not suppress faults
        assertThat(httpChaosProfile().withDegradationRampMillis(10_000L).degradationFactor(0L, 5000L), is(1.0));
    }

    @Test
    public void copyDuplicatesAllFields() {
        HttpChaosProfile original = httpChaosProfile()
            .withErrorStatus(503)
            .withRetryAfter("60")
            .withErrorProbability(0.5)
            .withDropConnectionProbability(0.2)
            .withLatency(Delay.milliseconds(100))
            .withSeed(7L)
            .withSucceedFirst(1)
            .withFailRequestCount(2)
            .withOutageAfterMillis(3L)
            .withOutageDurationMillis(4L)
            .withTruncateBodyAtFraction(0.5)
            .withMalformedBody(true)
            .withSlowResponseChunkSize(8)
            .withSlowResponseChunkDelay(Delay.milliseconds(50))
            .withQuotaName("q")
            .withQuotaLimit(5)
            .withQuotaWindowMillis(60000L)
            .withQuotaErrorStatus(429)
            .withDegradationRampMillis(10_000L);
        assertThat(original.copy(), is(equalTo(original)));
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
    public void dropConnectionProbabilityIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withDropConnectionProbability(0.5);
        HttpChaosProfile b = httpChaosProfile().withDropConnectionProbability(0.8);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void latencyIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withLatency(Delay.milliseconds(100));
        HttpChaosProfile b = httpChaosProfile().withLatency(Delay.milliseconds(200));
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void bodyCorruptionFieldsIncludedInEquals() {
        assertThat(httpChaosProfile().withTruncateBodyAtFraction(0.5),
            is(not(equalTo(httpChaosProfile().withTruncateBodyAtFraction(0.9)))));
        assertThat(httpChaosProfile().withMalformedBody(true),
            is(not(equalTo(httpChaosProfile().withMalformedBody(false)))));
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
    public void expectationWithChaosDropConnectionProbabilityRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(0.75)
                .withSeed(99L));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getDropConnectionProbability(), is(0.75));
        assertThat(deserialized[0].getChaos().getSeed(), is(99L));
    }

    @Test
    public void expectationWithChaosBodyCorruptionRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withTruncateBodyAtFraction(0.25)
                .withMalformedBody(true));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getTruncateBodyAtFraction(), is(0.25));
        assertThat(deserialized[0].getChaos().getMalformedBody(), is(true));
    }

    @Test
    public void expectationWithChaosSlowResponseRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withSlowResponseChunkSize(8)
                .withSlowResponseChunkDelay(Delay.milliseconds(250)));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getSlowResponseChunkSize(), is(8));
        assertThat(deserialized[0].getChaos().getSlowResponseChunkDelay().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(deserialized[0].getChaos().getSlowResponseChunkDelay().getValue(), is(250L));
    }

    @Test
    public void expectationWithChaosQuotaRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withQuotaName("acct-1")
                .withQuotaLimit(4)
                .withQuotaWindowMillis(60000L)
                .withQuotaErrorStatus(429));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getQuotaName(), is("acct-1"));
        assertThat(deserialized[0].getChaos().getQuotaLimit(), is(4));
        assertThat(deserialized[0].getChaos().getQuotaWindowMillis(), is(60000L));
        assertThat(deserialized[0].getChaos().getQuotaErrorStatus(), is(429));
    }

    @Test
    public void expectationWithChaosDegradationRampRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withDegradationRampMillis(30000L));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getDegradationRampMillis(), is(30000L));
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

    // --- succeedFirst validation tests ---

    @Test
    public void withSucceedFirstAcceptsNull() {
        httpChaosProfile().withSucceedFirst(null);
    }

    @Test
    public void withSucceedFirstAcceptsZero() {
        httpChaosProfile().withSucceedFirst(0);
    }

    @Test
    public void withSucceedFirstAcceptsPositive() {
        httpChaosProfile().withSucceedFirst(5);
    }

    @Test
    public void withSucceedFirstRejectsNegative() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withSucceedFirst(-1));
        assertThat(exception.getMessage(), is("succeedFirst must be >= 0, got -1"));
    }

    // --- failRequestCount validation tests ---

    @Test
    public void withFailRequestCountAcceptsNull() {
        httpChaosProfile().withFailRequestCount(null);
    }

    @Test
    public void withFailRequestCountAcceptsOne() {
        httpChaosProfile().withFailRequestCount(1);
    }

    @Test
    public void withFailRequestCountAcceptsLargeValue() {
        httpChaosProfile().withFailRequestCount(1000);
    }

    @Test
    public void withFailRequestCountRejectsZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withFailRequestCount(0));
        assertThat(exception.getMessage(), is("failRequestCount must be >= 1, got 0"));
    }

    @Test
    public void withFailRequestCountRejectsNegative() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withFailRequestCount(-1));
        assertThat(exception.getMessage(), is("failRequestCount must be >= 1, got -1"));
    }

    // --- countWindowEligible tests ---

    @Test
    public void countWindowEligibleBothNullAlwaysEligible() {
        HttpChaosProfile profile = httpChaosProfile();
        assertThat(profile.countWindowEligible(0), is(true));
        assertThat(profile.countWindowEligible(1), is(true));
        assertThat(profile.countWindowEligible(100), is(true));
    }

    @Test
    public void countWindowEligibleSucceedFirstOnly() {
        HttpChaosProfile profile = httpChaosProfile().withSucceedFirst(2);
        assertThat(profile.countWindowEligible(1), is(false));
        assertThat(profile.countWindowEligible(2), is(false));
        assertThat(profile.countWindowEligible(3), is(true));
        assertThat(profile.countWindowEligible(100), is(true));
    }

    @Test
    public void countWindowEligibleFailRequestCountOnly() {
        HttpChaosProfile profile = httpChaosProfile().withFailRequestCount(2);
        assertThat(profile.countWindowEligible(1), is(true));
        assertThat(profile.countWindowEligible(2), is(true));
        assertThat(profile.countWindowEligible(3), is(false));
    }

    @Test
    public void countWindowEligibleBothFieldsSet() {
        HttpChaosProfile profile = httpChaosProfile()
            .withSucceedFirst(2)
            .withFailRequestCount(3);
        assertThat(profile.countWindowEligible(1), is(false));
        assertThat(profile.countWindowEligible(2), is(false));
        assertThat(profile.countWindowEligible(3), is(true));
        assertThat(profile.countWindowEligible(4), is(true));
        assertThat(profile.countWindowEligible(5), is(true));
        assertThat(profile.countWindowEligible(6), is(false));
    }

    @Test
    public void countWindowEligibleFailOnlyNthRequest() {
        // succeedFirst=2, failRequestCount=1 → only #3 fails
        HttpChaosProfile profile = httpChaosProfile()
            .withSucceedFirst(2)
            .withFailRequestCount(1);
        assertThat(profile.countWindowEligible(1), is(false));
        assertThat(profile.countWindowEligible(2), is(false));
        assertThat(profile.countWindowEligible(3), is(true));
        assertThat(profile.countWindowEligible(4), is(false));
    }

    // --- equals/hashCode with new fields ---

    @Test
    public void succeedFirstIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withSucceedFirst(1);
        HttpChaosProfile b = httpChaosProfile().withSucceedFirst(2);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void failRequestCountIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withFailRequestCount(1);
        HttpChaosProfile b = httpChaosProfile().withFailRequestCount(2);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void equalProfilesWithCountFieldsAreEqual() {
        HttpChaosProfile a = httpChaosProfile()
            .withErrorStatus(503)
            .withSucceedFirst(2)
            .withFailRequestCount(3);
        HttpChaosProfile b = httpChaosProfile()
            .withErrorStatus(503)
            .withSucceedFirst(2)
            .withFailRequestCount(3);
        assertThat(a, is(equalTo(b)));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    // --- serialization round-trip with count fields ---

    @Test
    public void expectationWithChaosCountFieldsRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2)
                .withFailRequestCount(5));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getSucceedFirst(), is(2));
        assertThat(deserialized[0].getChaos().getFailRequestCount(), is(5));
    }

    @Test
    public void expectationWithChaosNoCountFieldsRoundTripsWithNulls() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos().getSucceedFirst() == null, is(true));
        assertThat(deserialized[0].getChaos().getFailRequestCount() == null, is(true));
    }

    // --- time-based outage window ---

    @Test
    public void shouldAcceptValidOutageFields() {
        HttpChaosProfile chaos = httpChaosProfile()
            .withOutageAfterMillis(0L)
            .withOutageDurationMillis(1L);
        assertThat(chaos.getOutageAfterMillis(), is(0L));
        assertThat(chaos.getOutageDurationMillis(), is(1L));

        HttpChaosProfile nullChaos = httpChaosProfile()
            .withOutageAfterMillis(null)
            .withOutageDurationMillis(null);
        assertThat(nullChaos.getOutageAfterMillis() == null, is(true));
        assertThat(nullChaos.getOutageDurationMillis() == null, is(true));
    }

    @Test
    public void shouldRejectNegativeOutageAfterMillis() {
        assertThrows(IllegalArgumentException.class, () -> httpChaosProfile().withOutageAfterMillis(-1L));
    }

    @Test
    public void shouldRejectOutageDurationMillisBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> httpChaosProfile().withOutageDurationMillis(0L));
    }

    @Test
    public void shouldIncludeOutageFieldsInEqualsAndHashCode() {
        HttpChaosProfile one = httpChaosProfile().withOutageAfterMillis(5000L).withOutageDurationMillis(10000L);
        HttpChaosProfile two = httpChaosProfile().withOutageAfterMillis(5000L).withOutageDurationMillis(10000L);
        HttpChaosProfile different = httpChaosProfile().withOutageAfterMillis(5000L).withOutageDurationMillis(9999L);
        assertThat(one, is(equalTo(two)));
        assertThat(one.hashCode(), is(two.hashCode()));
        assertThat(one, is(not(equalTo(different))));
    }

    @Test
    public void timeWindowEligibleReturnsTrueWhenNoWindowConfigured() {
        HttpChaosProfile chaos = httpChaosProfile().withErrorProbability(1.0);
        assertThat(chaos.timeWindowEligible(1_000L, 1_000_000L), is(true));
        assertThat(chaos.timeWindowEligible(0L, 0L), is(true));
    }

    @Test
    public void timeWindowEligibleGatesOnTheConfiguredWindow() {
        HttpChaosProfile chaos = httpChaosProfile()
            .withOutageAfterMillis(5_000L)
            .withOutageDurationMillis(10_000L);
        long anchor = 1_000_000L;
        // before the window opens
        assertThat(chaos.timeWindowEligible(anchor, anchor + 4_999L), is(false));
        // exactly at the window open
        assertThat(chaos.timeWindowEligible(anchor, anchor + 5_000L), is(true));
        // inside the window
        assertThat(chaos.timeWindowEligible(anchor, anchor + 10_000L), is(true));
        // exactly at the window close (exclusive) — healed
        assertThat(chaos.timeWindowEligible(anchor, anchor + 15_000L), is(false));
        // after the window — healed
        assertThat(chaos.timeWindowEligible(anchor, anchor + 20_000L), is(false));
    }

    @Test
    public void timeWindowEligibleFailsOpenWhenAnchorUnknown() {
        HttpChaosProfile chaos = httpChaosProfile().withOutageAfterMillis(5_000L).withOutageDurationMillis(10_000L);
        assertThat(chaos.timeWindowEligible(0L, 1_000_000L), is(true));
    }

    @Test
    public void timeWindowEligibleHonoursUnboundedDuration() {
        HttpChaosProfile chaos = httpChaosProfile().withOutageAfterMillis(5_000L);
        long anchor = 1_000_000L;
        assertThat(chaos.timeWindowEligible(anchor, anchor + 4_999L), is(false));
        assertThat(chaos.timeWindowEligible(anchor, anchor + 5_000L), is(true));
        assertThat(chaos.timeWindowEligible(anchor, anchor + 1_000_000L), is(true));
    }

    @Test
    public void expectationWithChaosOutageFieldsRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withOutageAfterMillis(5000L)
                .withOutageDurationMillis(10000L));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getOutageAfterMillis(), is(5000L));
        assertThat(deserialized[0].getChaos().getOutageDurationMillis(), is(10000L));
    }
}
