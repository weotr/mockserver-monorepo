package org.mockserver.grpc.connect;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ConnectErrorTest {

    @Test
    public void shouldMapConnectCodeToWireStringAndHttpStatus() {
        assertThat(ConnectError.Code.NOT_FOUND.code(), is("not_found"));
        assertThat(ConnectError.Code.NOT_FOUND.httpStatus(), is(404));
        assertThat(ConnectError.Code.INVALID_ARGUMENT.code(), is("invalid_argument"));
        assertThat(ConnectError.Code.INVALID_ARGUMENT.httpStatus(), is(400));
        assertThat(ConnectError.Code.UNAUTHENTICATED.httpStatus(), is(401));
        assertThat(ConnectError.Code.PERMISSION_DENIED.httpStatus(), is(403));
        assertThat(ConnectError.Code.RESOURCE_EXHAUSTED.httpStatus(), is(429));
        assertThat(ConnectError.Code.UNAVAILABLE.httpStatus(), is(503));
        assertThat(ConnectError.Code.INTERNAL.httpStatus(), is(500));
        // mappings that diverge from a naive guess — verified against connectrpc/connect-go codeToHTTP
        assertThat(ConnectError.Code.FAILED_PRECONDITION.httpStatus(), is(400));
        assertThat(ConnectError.Code.UNIMPLEMENTED.httpStatus(), is(501));
        assertThat(ConnectError.Code.DEADLINE_EXCEEDED.httpStatus(), is(504));
        assertThat(ConnectError.Code.CANCELED.httpStatus(), is(499));
        assertThat(ConnectError.Code.ALREADY_EXISTS.httpStatus(), is(409));
        assertThat(ConnectError.Code.ABORTED.httpStatus(), is(409));
        assertThat(ConnectError.Code.OUT_OF_RANGE.httpStatus(), is(400));
        assertThat(ConnectError.Code.DATA_LOSS.httpStatus(), is(500));
        assertThat(ConnectError.Code.UNKNOWN.httpStatus(), is(500));
    }

    @Test
    public void shouldResolveCodeFromWireString() {
        assertThat(ConnectError.Code.fromString("not_found"), is(ConnectError.Code.NOT_FOUND));
        assertThat(ConnectError.Code.fromString("NOT_FOUND"), is(ConnectError.Code.NOT_FOUND));
        assertThat(ConnectError.Code.fromString("  Invalid_Argument "), is(ConnectError.Code.INVALID_ARGUMENT));
    }

    @Test
    public void shouldDegradeUnknownCodeToUnknown() {
        assertThat(ConnectError.Code.fromString(null), is(ConnectError.Code.UNKNOWN));
        assertThat(ConnectError.Code.fromString("not_a_real_code"), is(ConnectError.Code.UNKNOWN));
    }

    @Test
    public void shouldStoreWireCodeWhenBuiltFromEnum() {
        ConnectError error = ConnectError.connectError(ConnectError.Code.NOT_FOUND, "missing");
        assertThat(error.getCode(), is("not_found"));
        assertThat(error.getMessage(), is("missing"));
        assertThat(error.httpStatus(), is(404));
    }

    @Test
    public void shouldStoreWireCodeWhenBuiltFromString() {
        ConnectError error = ConnectError.connectError("unauthenticated", "no token");
        assertThat(error.getCode(), is("unauthenticated"));
        assertThat(error.httpStatus(), is(401));
    }

    @Test
    public void shouldSupportDetails() {
        ConnectError error = ConnectError.connectError(ConnectError.Code.INTERNAL, "boom")
            .addDetail("detail-one")
            .addDetail("detail-two");
        assertThat(error.getDetails(), contains("detail-one", "detail-two"));
    }
}
