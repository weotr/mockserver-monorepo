package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.RecoverAfter.recoverAfter;

/**
 * Pure model tests for {@link RecoverAfter}: builder, getters, equals/hashCode, and round-trip
 * through {@link HttpResponse#clone()}.
 */
public class RecoverAfterTest {

    @Test
    public void shouldBuildWithStaticFactory() {
        RecoverAfter recoverAfter = recoverAfter(3);
        assertThat(recoverAfter.getFailTimes(), is(3));
        assertThat(recoverAfter.getFailResponse(), is(nullValue()));
        assertThat(recoverAfter.getIdempotencyHeader(), is(nullValue()));
    }

    @Test
    public void shouldSetAllFields() {
        HttpResponse failResponse = response().withStatusCode(503).withBody("down");
        RecoverAfter recoverAfter = new RecoverAfter()
            .withFailTimes(2)
            .withFailResponse(failResponse)
            .withIdempotencyHeader("Idempotency-Key");

        assertThat(recoverAfter.getFailTimes(), is(2));
        assertThat(recoverAfter.getFailResponse(), is(failResponse));
        assertThat(recoverAfter.getIdempotencyHeader(), is("Idempotency-Key"));
    }

    @Test
    public void shouldBeEqualWhenFieldsMatch() {
        RecoverAfter a = new RecoverAfter().withFailTimes(2).withIdempotencyHeader("Key");
        RecoverAfter b = new RecoverAfter().withFailTimes(2).withIdempotencyHeader("Key");
        assertThat(a, is(b));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenFieldsDiffer() {
        RecoverAfter a = new RecoverAfter().withFailTimes(2);
        RecoverAfter b = new RecoverAfter().withFailTimes(3);
        assertThat(a, is(not(b)));
    }

    @Test
    public void shouldNotBeEqualWhenFailResponseDiffers() {
        RecoverAfter a = new RecoverAfter().withFailTimes(2).withFailResponse(response().withStatusCode(503));
        RecoverAfter b = new RecoverAfter().withFailTimes(2).withFailResponse(response().withStatusCode(500));
        assertThat(a, is(not(b)));
    }

    @Test
    public void shouldRoundTripThroughResponseClone() {
        HttpResponse failResponse = response().withStatusCode(503).withHeader("Retry-After", "1");
        RecoverAfter recoverAfter = new RecoverAfter()
            .withFailTimes(3)
            .withFailResponse(failResponse)
            .withIdempotencyHeader("Idempotency-Key");
        HttpResponse original = response().withStatusCode(200).withBody("ok").withRecoverAfter(recoverAfter);

        HttpResponse cloned = original.clone();

        assertThat(cloned.getRecoverAfter(), is(recoverAfter));
        assertThat(cloned, is(original));
    }

    @Test
    public void responseWithoutRecoverAfterHasNullRecoverAfter() {
        HttpResponse plain = response().withStatusCode(200).withBody("ok");
        assertThat(plain.getRecoverAfter(), is(nullValue()));
        assertThat(plain.clone().getRecoverAfter(), is(nullValue()));
    }
}
