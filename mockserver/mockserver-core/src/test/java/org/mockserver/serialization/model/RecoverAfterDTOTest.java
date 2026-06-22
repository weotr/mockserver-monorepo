package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RecoverAfter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.HttpResponse.response;

/**
 * Round-trip tests for {@link RecoverAfterDTO}: model -&gt; DTO -&gt; model preserves all fields,
 * and absent nested fields stay null.
 */
public class RecoverAfterDTOTest {

    @Test
    public void shouldCaptureFieldsInConstructor() {
        HttpResponse failResponse = response().withStatusCode(503).withBody("down");
        RecoverAfter recoverAfter = new RecoverAfter()
            .withFailTimes(3)
            .withFailResponse(failResponse)
            .withIdempotencyHeader("Idempotency-Key");

        RecoverAfterDTO dto = new RecoverAfterDTO(recoverAfter);

        assertThat(dto.getFailTimes(), is(3));
        assertThat(dto.getIdempotencyHeader(), is("Idempotency-Key"));
        assertThat(dto.getFailResponse(), is(new HttpResponseDTO(failResponse)));
    }

    @Test
    public void shouldBuildObject() {
        HttpResponse failResponse = response().withStatusCode(503).withBody("down");
        RecoverAfter recoverAfter = new RecoverAfter()
            .withFailTimes(2)
            .withFailResponse(failResponse)
            .withIdempotencyHeader("Idempotency-Key");

        RecoverAfter built = new RecoverAfterDTO(recoverAfter).buildObject();

        assertThat(built, is(recoverAfter));
    }

    @Test
    public void shouldRoundTripWithDefaultFailResponse() {
        RecoverAfter recoverAfter = RecoverAfter.recoverAfter(3);

        RecoverAfter built = new RecoverAfterDTO(recoverAfter).buildObject();

        assertThat(built.getFailTimes(), is(3));
        assertThat(built.getFailResponse(), is(nullValue()));
        assertThat(built.getIdempotencyHeader(), is(nullValue()));
        assertThat(built, is(recoverAfter));
    }

    @Test
    public void shouldHandleNullInput() {
        RecoverAfterDTO dto = new RecoverAfterDTO(null);
        assertThat(dto.getFailTimes(), is(nullValue()));
        assertThat(dto.getFailResponse(), is(nullValue()));
        assertThat(dto.getIdempotencyHeader(), is(nullValue()));
    }

    @Test
    public void shouldReturnValuesSetInSetters() {
        HttpResponseDTO failResponse = new HttpResponseDTO(response().withStatusCode(500));
        RecoverAfterDTO dto = new RecoverAfterDTO()
            .setFailTimes(4)
            .setFailResponse(failResponse)
            .setIdempotencyHeader("X-Key");

        assertThat(dto.getFailTimes(), is(4));
        assertThat(dto.getFailResponse(), is(failResponse));
        assertThat(dto.getIdempotencyHeader(), is("X-Key"));
    }
}
