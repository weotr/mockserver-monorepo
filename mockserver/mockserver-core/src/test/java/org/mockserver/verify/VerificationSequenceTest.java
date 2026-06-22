package org.mockserver.verify;

import org.junit.Test;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class VerificationSequenceTest {

    @Test
    public void shouldReturnValuesSetInSetter() {
        // when
        VerificationSequence verification = new VerificationSequence()
                .withRequests(request("one"), request("two"), request("three"));

        // then
        assertThat(verification.getHttpRequests(), is(Arrays.asList(request("one"), request("two"), request("three"))));
    }

    @Test
    public void shouldReturnResponsesSetWithVarargs() {
        // when
        HttpResponse response1 = response().withStatusCode(200);
        HttpResponse response2 = response().withStatusCode(404);
        VerificationSequence verification = new VerificationSequence()
            .withResponses(response1, response2);

        // then
        assertThat(verification.getHttpResponses(), is(Arrays.asList(response1, response2)));
    }

    @Test
    public void shouldReturnResponsesSetWithList() {
        // when
        HttpResponse response1 = response().withStatusCode(200);
        HttpResponse response2 = response().withStatusCode(404);
        VerificationSequence verification = new VerificationSequence()
            .withResponses(Arrays.asList(response1, response2));

        // then
        assertThat(verification.getHttpResponses(), is(Arrays.asList(response1, response2)));
    }

    @Test
    public void shouldReturnEmptyResponsesByDefault() {
        // when
        VerificationSequence verification = new VerificationSequence();

        // then
        assertThat(verification.getHttpResponses(), is(Collections.emptyList()));
    }

}