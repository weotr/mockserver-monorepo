package org.mockserver.grpc.connect;

import org.junit.Test;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ConnectResponseTest {

    @Test
    public void shouldBuildSuccessResponseWithJsonContentType() {
        HttpResponse response = ConnectResponse.success("{\"greeting\":\"hello\"}");

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
        assertThat(response.getBodyAsString(), is("{\"greeting\":\"hello\"}"));
    }

    @Test
    public void shouldDefaultEmptySuccessBodyToEmptyObject() {
        HttpResponse response = ConnectResponse.success(null);

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("{}"));
    }

    @Test
    public void shouldBuildSuccessResponseWithProtoContentType() {
        HttpResponse response = ConnectResponse.success("binary-ish", ConnectResponse.CONNECT_PROTO_CONTENT_TYPE);

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), is("application/proto"));
    }

    @Test
    public void shouldBuildErrorEnvelopeWithMappedHttpStatus() {
        HttpResponse response = ConnectResponse.error(ConnectError.Code.NOT_FOUND, "user 42 not found");

        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getFirstHeader("content-type"), is("application/json"));
        String body = response.getBodyAsString();
        assertThat(body, containsString("\"code\":\"not_found\""));
        assertThat(body, containsString("\"message\":\"user 42 not found\""));
    }

    @Test
    public void shouldMapUnauthenticatedToHttp401() {
        HttpResponse response = ConnectResponse.error(ConnectError.Code.UNAUTHENTICATED, "no token");
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("unauthenticated"));
    }

    @Test
    public void shouldMapResourceExhaustedToHttp429() {
        HttpResponse response = ConnectResponse.error(ConnectError.Code.RESOURCE_EXHAUSTED, "slow down");
        assertThat(response.getStatusCode(), is(429));
    }

    @Test
    public void shouldSerialiseDetailsArray() {
        ConnectError error = ConnectError.connectError(ConnectError.Code.INVALID_ARGUMENT, "bad")
            .addDetail("field x is required");
        HttpResponse response = ConnectResponse.error(error);

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("field x is required"));
        assertThat(response.getBodyAsString(), containsString("\"details\""));
    }

    @Test
    public void shouldOmitDetailsWhenAbsent() {
        HttpResponse response = ConnectResponse.error(ConnectError.Code.INTERNAL, "boom");
        assertThat(response.getBodyAsString(), not(containsString("details")));
    }

    @Test
    public void shouldDegradeNullErrorToUnknown() {
        HttpResponse response = ConnectResponse.error((ConnectError) null);
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), containsString("unknown"));
    }
}
