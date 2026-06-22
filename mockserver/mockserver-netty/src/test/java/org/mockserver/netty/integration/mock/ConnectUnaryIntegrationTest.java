package org.mockserver.netty.integration.mock;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.connect.ConnectError;
import org.mockserver.grpc.connect.ConnectResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Verifies Connect protocol (buf.build Connect) unary mocking. Connect unary requests are ordinary
 * HTTP POSTs to {@code /package.Service/Method} with {@code application/json}, so they flow through
 * normal expectation matching; the {@link ConnectResponse} helpers provide correct content-type and
 * Connect error framing. Real gRPC ({@code application/grpc}) traffic must remain unaffected.
 */
public class ConnectUnaryIntegrationTest {

    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    /**
     * Sends a raw HTTP/1.1 POST and returns the full raw response (status line + headers + body).
     */
    private String sendPost(String path, String body, String contentType) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "POST " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void shouldReturnConnectUnarySuccessJson() throws Exception {
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Greeting"))
            .respond(ConnectResponse.success("{\"greeting\":\"Hello World\"}"));

        String response = sendPost(
            "/com.example.grpc.GreetingService/Greeting",
            "{\"name\":\"World\"}",
            "application/json");

        assertThat(response, containsString("HTTP/1.1 200"));
        assertThat(response.toLowerCase(), containsString("content-type: application/json"));
        assertThat(response, containsString("\"greeting\":\"Hello World\""));
    }

    @Test
    public void shouldReturnConnectUnaryErrorEnvelopeWithMappedStatus() throws Exception {
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Greeting"))
            .respond(ConnectResponse.error(ConnectError.Code.NOT_FOUND, "greeting not found"));

        String response = sendPost(
            "/com.example.grpc.GreetingService/Greeting",
            "{\"name\":\"World\"}",
            "application/json");

        assertThat(response, containsString("HTTP/1.1 404"));
        assertThat(response.toLowerCase(), containsString("content-type: application/json"));
        assertThat(response, containsString("\"code\":\"not_found\""));
        assertThat(response, containsString("\"message\":\"greeting not found\""));
    }

    @Test
    public void shouldMapUnauthenticatedConnectErrorToHttp401() throws Exception {
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Greeting"))
            .respond(ConnectResponse.error(ConnectError.Code.UNAUTHENTICATED, "no token"));

        String response = sendPost(
            "/com.example.grpc.GreetingService/Greeting",
            "{}",
            "application/json");

        assertThat(response, containsString("HTTP/1.1 401"));
        assertThat(response, containsString("\"code\":\"unauthenticated\""));
    }

    @Test
    public void shouldMatchConnectRequestByJsonBody() throws Exception {
        // body-level matching works because Connect unary is plain HTTP + JSON
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Greeting")
                .withBody("{\"name\":\"Alice\"}"))
            .respond(ConnectResponse.success("{\"greeting\":\"Hello Alice\"}"));

        String matched = sendPost(
            "/com.example.grpc.GreetingService/Greeting",
            "{\"name\":\"Alice\"}",
            "application/json");
        assertThat(matched, containsString("HTTP/1.1 200"));
        assertThat(matched, containsString("Hello Alice"));

        // a different body should not match this expectation
        String unmatched = sendPost(
            "/com.example.grpc.GreetingService/Greeting",
            "{\"name\":\"Bob\"}",
            "application/json");
        assertThat(unmatched, containsString("HTTP/1.1 404"));
    }

    @Test
    public void shouldNotAffectRealGrpcContentTypeRouting() throws Exception {
        // A request with application/grpc content type must NOT be served by the Connect/plain-HTTP
        // expectation; with no descriptors loaded it falls through to standard handling (404), proving
        // the Connect helper does not capture or alter real gRPC traffic.
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("POST")
                .withPath("/com.example.grpc.GreetingService/Greeting")
                .withHeader("content-type", "application/json"))
            .respond(ConnectResponse.success("{\"greeting\":\"json only\"}"));

        String grpcResponse = sendPost(
            "/com.example.grpc.GreetingService/Greeting",
            "ignored",
            "application/grpc");

        // The application/json-scoped expectation must not match an application/grpc request.
        assertThat(grpcResponse, not(containsString("json only")));
    }
}
