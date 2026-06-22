package org.mockserver.verify;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.verify.VerificationTimes.atLeast;
import static org.mockserver.verify.Verification.verification;

/**
 * @author jamesdbloom
 */
public class VerificationTest {

    @Test
    public void shouldReturnValuesSetInSetter() {
        // when
        HttpRequest request = request();
        VerificationTimes times = atLeast(2);
        Verification verification = verification()
                .withRequest(request)
                .withTimes(times);

        // then
        assertThat(verification.getHttpRequest(), sameInstance(request));
        assertThat(verification.getTimes(), sameInstance(times));
    }

    @Test
    public void shouldReturnResponseSetInSetter() {
        // when
        HttpResponse response = response().withStatusCode(200);
        Verification verification = verification()
            .withResponse(response);

        // then
        assertThat(verification.getHttpResponse(), sameInstance(response));
    }

    @Test
    public void shouldReturnNullResponseByDefault() {
        // when
        Verification verification = verification();

        // then
        assertThat(verification.getHttpResponse(), nullValue());
    }

    @Test
    public void shouldSupportRequestAndResponseTogether() {
        // when
        HttpRequest request = request();
        HttpResponse response = response().withStatusCode(200);
        VerificationTimes times = atLeast(1);
        Verification verification = verification()
            .withRequest(request)
            .withResponse(response)
            .withTimes(times);

        // then
        assertThat(verification.getHttpRequest(), sameInstance(request));
        assertThat(verification.getHttpResponse(), sameInstance(response));
        assertThat(verification.getTimes(), sameInstance(times));
    }

}
