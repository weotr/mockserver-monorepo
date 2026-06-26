package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.verify.VerificationSequence;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class VerificationSequenceDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // given
        VerificationSequence verification = new VerificationSequence()
                .withRequests(
                        request("one"),
                        request("two"),
                        request("three")
                );

        // when
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(verification);

        // then
        assertThat(verificationSequenceDTO.getHttpRequests(), is(Arrays.asList(
                new HttpRequestDTO(request("one")),
                new HttpRequestDTO(request("two")),
                new HttpRequestDTO(request("three"))
        )));
    }

    @Test
    public void shouldBuildObject() {
        // given
        VerificationSequence verification = new VerificationSequence()
                .withRequests(
                        request("one"),
                        request("two"),
                        request("three")
                );

        // when
        VerificationSequence builtVerification = new VerificationSequenceDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getHttpRequests(), is(Arrays.asList(
                request("one"),
                request("two"),
                request("three")
        )));
    }

    @Test
    public void shouldReturnValuesSetInSetter() {
        // given
        VerificationSequence verification = new VerificationSequence();

        // when
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(verification);
        verificationSequenceDTO.setHttpRequests(Arrays.asList(
                new HttpRequestDTO(request("one")),
                new HttpRequestDTO(request("two")),
                new HttpRequestDTO(request("three"))
        ));

        // then
        assertThat(verificationSequenceDTO.getHttpRequests(), is(Arrays.asList(
                new HttpRequestDTO(request("one")),
                new HttpRequestDTO(request("two")),
                new HttpRequestDTO(request("three"))
        )));
    }

    @Test
    public void shouldHandleNullObjectInput() {
        // when
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(null);

        // then
        assertThat(verificationSequenceDTO.getHttpRequests(), is(Collections.<HttpRequestDTO>emptyList()));
    }

    @Test
    public void shouldHandleNullFieldInput() {
        // when
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(new VerificationSequence());

        // then
        assertThat(verificationSequenceDTO.getHttpRequests(), is(Collections.<HttpRequestDTO>emptyList()));
        assertThat(verificationSequenceDTO.getHttpResponses(), is(Collections.<HttpResponseDTO>emptyList()));
    }

    @Test
    public void shouldReturnResponsesSetInConstructor() {
        // given
        HttpResponse response1 = response().withStatusCode(200);
        HttpResponse response2 = response().withStatusCode(404);
        VerificationSequence verification = new VerificationSequence()
            .withRequests(request("one"))
            .withResponses(response1, response2);

        // when
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(verification);

        // then
        assertThat(verificationSequenceDTO.getHttpResponses(), is(Arrays.asList(
            new HttpResponseDTO(response1),
            new HttpResponseDTO(response2)
        )));
    }

    @Test
    public void shouldBuildObjectWithResponses() {
        // given
        HttpResponse response1 = response().withStatusCode(200);
        HttpResponse response2 = response().withStatusCode(404);
        VerificationSequence verification = new VerificationSequence()
            .withRequests(request("one"))
            .withResponses(response1, response2);

        // when
        VerificationSequence builtVerification = new VerificationSequenceDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getHttpResponses(), is(Arrays.asList(response1, response2)));
    }

    @Test
    public void shouldReturnTimeoutSetInConstructor() {
        // given
        VerificationSequence verification = new VerificationSequence()
            .withRequests(request("one"))
            .withTimeout(5000L);

        // when
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(verification);

        // then
        assertThat(verificationSequenceDTO.getTimeout(), is(5000L));
    }

    @Test
    public void shouldBuildObjectWithTimeout() {
        // given
        VerificationSequence verification = new VerificationSequence()
            .withRequests(request("one"))
            .withTimeout(2500L);

        // when
        VerificationSequence builtVerification = new VerificationSequenceDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getTimeout(), is(2500L));
    }

    @Test
    public void shouldReturnTimeoutSetInSetter() {
        // given
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(new VerificationSequence());

        // when
        verificationSequenceDTO.setTimeout(1000L);

        // then
        assertThat(verificationSequenceDTO.getTimeout(), is(1000L));
    }

    @Test
    public void shouldBuildObjectWithNullTimeout() {
        // given
        VerificationSequence verification = new VerificationSequence().withRequests(request("one"));

        // when
        VerificationSequence builtVerification = new VerificationSequenceDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getTimeout(), nullValue());
    }

    @Test
    public void shouldReturnResponsesSetInSetter() {
        // given
        VerificationSequenceDTO verificationSequenceDTO = new VerificationSequenceDTO(new VerificationSequence());

        // when
        verificationSequenceDTO.setHttpResponses(Arrays.asList(
            new HttpResponseDTO(response().withStatusCode(200)),
            new HttpResponseDTO(response().withStatusCode(500))
        ));

        // then
        assertThat(verificationSequenceDTO.getHttpResponses(), is(Arrays.asList(
            new HttpResponseDTO(response().withStatusCode(200)),
            new HttpResponseDTO(response().withStatusCode(500))
        )));
    }

}