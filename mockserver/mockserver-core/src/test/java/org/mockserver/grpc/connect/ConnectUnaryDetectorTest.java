package org.mockserver.grpc.connect;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ConnectUnaryDetectorTest {

    @Test
    public void shouldDetectConnectUnaryJsonPost() {
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/json", "/pkg.Svc/Method"), is(true));
    }

    @Test
    public void shouldDetectConnectUnaryProtoPost() {
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/proto", "/pkg.Svc/Method"), is(true));
    }

    @Test
    public void shouldDetectWithCharsetSuffix() {
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/json; charset=utf-8", "/pkg.Svc/Method"), is(true));
    }

    @Test
    public void shouldNotDetectRealGrpc() {
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/grpc", "/pkg.Svc/Method"), is(false));
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/grpc+proto", "/pkg.Svc/Method"), is(false));
    }

    @Test
    public void shouldNotDetectGrpcWeb() {
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/grpc-web", "/pkg.Svc/Method"), is(false));
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/grpc-web+proto", "/pkg.Svc/Method"), is(false));
    }

    @Test
    public void shouldNotDetectGetRequests() {
        assertThat(ConnectUnaryDetector.isConnectUnary("GET", "application/json", "/pkg.Svc/Method"), is(false));
    }

    @Test
    public void shouldNotDetectPlainRestPaths() {
        // single segment, or no package dot in service segment
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/json", "/users"), is(false));
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/json", "/Service/Method"), is(false));
        assertThat(ConnectUnaryDetector.isConnectUnary("POST", "application/json", "/a/b/c"), is(false));
    }

    @Test
    public void shouldParseServiceAndMethod() {
        String[] parts = ConnectUnaryDetector.parseServiceMethod("/com.example.grpc.GreetingService/Greeting");
        assertThat(parts[0], is("com.example.grpc.GreetingService"));
        assertThat(parts[1], is("Greeting"));
    }

    @Test
    public void shouldReturnEmptyForMalformedPath() {
        String[] parts = ConnectUnaryDetector.parseServiceMethod("/notapath");
        assertThat(parts[0], is(""));
        assertThat(parts[1], is(""));
    }

    @Test
    public void shouldNotValidateWhenNoDescriptorLoaded() {
        // null store -> no validation, returns null (valid)
        assertThat(ConnectUnaryDetector.validateRequestBody(null, "/pkg.Svc/Method", "{}"), is(nullValue()));
    }
}
