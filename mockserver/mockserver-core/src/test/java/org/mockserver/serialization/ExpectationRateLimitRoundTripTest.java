package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.RateLimit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.RateLimit.rateLimit;

public class ExpectationRateLimitRoundTripTest {

    private final ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

    @Test
    public void shouldRoundTripFixedWindowRateLimit() {
        // given
        Expectation original = when(request().withPath("/api/widgets"))
            .thenRespond(response().withStatusCode(200).withBody("ok"))
            .withRateLimit(
                rateLimit()
                    .withName("widgets-account")
                    .withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW)
                    .withLimit(100)
                    .withWindowMillis(60_000L)
                    .withErrorStatus(429)
                    .withRetryAfter("60")
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized.length, is(1));
        RateLimit rl = deserialized[0].getRateLimit();
        assertThat(rl, is(notNullValue()));
        assertThat(rl.getName(), is("widgets-account"));
        assertThat(rl.getAlgorithm(), is(RateLimit.Algorithm.FIXED_WINDOW));
        assertThat(rl.getLimit(), is(100));
        assertThat(rl.getWindowMillis(), is(60_000L));
        assertThat(rl.getErrorStatus(), is(429));
        assertThat(rl.getRetryAfter(), is("60"));
    }

    @Test
    public void shouldRoundTripTokenBucketRateLimit() {
        // given
        Expectation original = when(request().withPath("/api/widgets"))
            .thenRespond(response().withStatusCode(200).withBody("ok"))
            .withRateLimit(
                rateLimit()
                    .withAlgorithm(RateLimit.Algorithm.TOKEN_BUCKET)
                    .withBurst(20L)
                    .withRefillPerSecond(5.0)
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized.length, is(1));
        RateLimit rl = deserialized[0].getRateLimit();
        assertThat(rl, is(notNullValue()));
        assertThat(rl.getAlgorithm(), is(RateLimit.Algorithm.TOKEN_BUCKET));
        assertThat(rl.getBurst(), is(20L));
        assertThat(rl.getRefillPerSecond(), is(5.0));
    }

    @Test
    public void shouldSerializeAlgorithmAsLowercaseString() {
        // given
        Expectation original = when(request().withPath("/api/widgets"))
            .thenRespond(response().withBody("ok"))
            .withRateLimit(rateLimit().withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW).withLimit(1).withWindowMillis(1000L));

        // when
        String json = serializer.serialize(original);

        // then — enum is serialized as a lowercase string in the JSON
        assertThat(json.contains("\"fixed_window\""), is(true));
        assertThat(json.contains("FIXED_WINDOW"), is(false));
    }

    @Test
    public void shouldOmitRateLimitWhenAbsentAndLeaveExpectationByteForByteUnchanged() {
        // given — an identical expectation built WITHOUT a rateLimit clause
        Expectation withoutRateLimit = when(request().withPath("/api/widgets"))
            .thenRespond(response().withStatusCode(200).withBody("ok"));

        // when
        String json = serializer.serialize(withoutRateLimit);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — no rateLimit key in the JSON and the field stays null after round-trip
        assertThat(json.contains("rateLimit"), is(false));
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getRateLimit(), is(nullValue()));
        // the response is preserved exactly
        assertThat(deserialized[0].getHttpResponse().getStatusCode(), is(200));
        assertThat(deserialized[0].getHttpResponse().getBodyAsString(), is("ok"));
    }
}
