package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.verify.Disposition;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationTimes;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.verify.Verification.verification;

public class VerificationDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // given
        HttpRequest request = request();
        VerificationTimes times = VerificationTimes.atLeast(1);
        Verification verification = verification()
                .withRequest(request)
                .withTimes(times);

        // when
        VerificationDTO verificationDTO = new VerificationDTO(verification);

        // then
        assertThat(verificationDTO.getHttpRequest(), is(new HttpRequestDTO(request)));
        assertThat(verificationDTO.getTimes(), is(new VerificationTimesDTO(times)));
    }

    @Test
    public void shouldBuildObject() {
        // given
        HttpRequest request = request();
        VerificationTimes times = VerificationTimes.atLeast(1);
        Verification verification = verification()
                .withRequest(request)
                .withTimes(times);

        // when
        Verification builtVerification = new VerificationDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getHttpRequest(), is(request));
        assertThat(builtVerification.getTimes(), is(times));
    }

    @Test
    public void shouldReturnValuesSetInSetter() {
        // given
        HttpRequestDTO request = new HttpRequestDTO(request());
        VerificationTimesDTO times = new VerificationTimesDTO(VerificationTimes.atLeast(1));
        Verification verification = verification();

        // when
        VerificationDTO verificationDTO = new VerificationDTO(verification);
        verificationDTO.setHttpRequest(request);
        verificationDTO.setTimes(times);

        // then
        assertThat(verificationDTO.getHttpRequest(), is(request));
        assertThat(verificationDTO.getTimes(), is(times));
    }

    @Test
    public void shouldHandleNullObjectInput() {
        // when
        VerificationDTO verificationDTO = new VerificationDTO(null);

        // then
        assertThat(verificationDTO.getHttpRequest(), is(nullValue()));
        assertThat(verificationDTO.getTimes(), is(nullValue()));
    }

    @Test
    public void shouldHandleNullFieldInput() {
        // when
        VerificationDTO verificationDTO = new VerificationDTO(new Verification());

        // then
        assertThat(verificationDTO.getHttpRequest(), nullValue());
        assertThat(verificationDTO.getHttpResponse(), nullValue());
        assertThat(verificationDTO.getTimes(), is(new VerificationTimesDTO(VerificationTimes.atLeast(1))));
    }

    @Test
    public void shouldReturnResponseSetInConstructor() {
        // given
        HttpResponse response = response().withStatusCode(200);
        Verification verification = verification()
            .withRequest(request())
            .withResponse(response);

        // when
        VerificationDTO verificationDTO = new VerificationDTO(verification);

        // then
        assertThat(verificationDTO.getHttpResponse(), is(new HttpResponseDTO(response)));
    }

    @Test
    public void shouldBuildObjectWithResponse() {
        // given
        HttpRequest request = request();
        HttpResponse response = response().withStatusCode(200);
        Verification verification = verification()
            .withRequest(request)
            .withResponse(response);

        // when
        Verification builtVerification = new VerificationDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getHttpRequest(), is(request));
        assertThat(builtVerification.getHttpResponse(), is(response));
    }

    @Test
    public void shouldReturnResponseSetInSetter() {
        // given
        HttpResponseDTO response = new HttpResponseDTO(response().withStatusCode(200));
        VerificationDTO verificationDTO = new VerificationDTO(verification());

        // when
        verificationDTO.setHttpResponse(response);

        // then
        assertThat(verificationDTO.getHttpResponse(), is(response));
    }

    @Test
    public void shouldReturnDispositionSetInConstructor() {
        // given
        Verification verification = verification()
            .withRequest(request())
            .withDisposition(Disposition.FORWARDED);

        // when
        VerificationDTO verificationDTO = new VerificationDTO(verification);

        // then
        assertThat(verificationDTO.getDisposition(), is(Disposition.FORWARDED));
    }

    @Test
    public void shouldBuildObjectWithDisposition() {
        // given
        Verification verification = verification()
            .withRequest(request())
            .withDisposition(Disposition.MOCKED);

        // when
        Verification builtVerification = new VerificationDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getDisposition(), is(Disposition.MOCKED));
    }

    @Test
    public void shouldReturnDispositionSetInSetter() {
        // given
        VerificationDTO verificationDTO = new VerificationDTO(verification());

        // when
        verificationDTO.setDisposition(Disposition.FORWARDED);

        // then
        assertThat(verificationDTO.getDisposition(), is(Disposition.FORWARDED));
    }

    @Test
    public void shouldBuildObjectWithNullDisposition() {
        // given
        Verification verification = verification().withRequest(request());

        // when
        Verification builtVerification = new VerificationDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getDisposition(), nullValue());
    }

    @Test
    public void shouldBuildObjectWithNullResponse() {
        // given
        HttpRequest request = request();
        Verification verification = verification()
            .withRequest(request);

        // when
        Verification builtVerification = new VerificationDTO(verification).buildObject();

        // then
        assertThat(builtVerification.getHttpRequest(), is(request));
        assertThat(builtVerification.getHttpResponse(), nullValue());
    }

}