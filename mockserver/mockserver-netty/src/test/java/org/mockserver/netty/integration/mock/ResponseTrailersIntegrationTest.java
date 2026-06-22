package org.mockserver.netty.integration.mock;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural integration test for general HTTP response trailers (trailing headers)
 * over HTTP/1.1. Uses a raw socket so the on-the-wire framing -- chunked transfer
 * encoding, the announcing {@code Trailer} header, and the trailing header block after
 * the final chunk -- can be asserted directly.
 */
public class ResponseTrailersIntegrationTest {

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

    private String sendRequestReadRawResponse(String path) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            // Connection: close gives us a clean EOF so we can read the entire raw response,
            // trailing header block included, with a single toString().
            output.write(("GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void shouldSendResponseTrailersOverHttp1AsChunkedWithTrailerHeader() throws Exception {
        // given
        mockServerClient
            .when(request().withMethod("GET").withPath("/with-trailers"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("hello trailer world")
                    .withTrailer("x-checksum", "abc123")
                    .withTrailer("x-signature", "deadbeef")
            );

        // when
        String raw = sendRequestReadRawResponse("/with-trailers");

        // then -- chunked encoding + announcing Trailer header + no fixed Content-Length
        String lower = raw.toLowerCase();
        assertThat(raw, containsString("HTTP/1.1 200"));
        assertThat(lower, containsString("transfer-encoding: chunked"));
        assertThat(lower, not(containsString("content-length:")));
        // Trailer header must announce the trailer field names
        assertThat(lower, containsString("trailer:"));
        assertThat(lower, containsString("x-checksum"));
        assertThat(lower, containsString("x-signature"));
        // body present
        assertThat(raw, containsString("hello trailer world"));
        // the trailing header block (after the terminating 0-length chunk) carries the values
        assertThat(raw, containsString("abc123"));
        assertThat(raw, containsString("deadbeef"));
        // and crucially the trailer values must appear AFTER the final-chunk terminator
        // (0\r\n), i.e. genuinely in the trailer block and not inside the body or headers.
        int finalChunkIndex = raw.indexOf("\r\n0\r\n");
        assertThat("final-chunk terminator (0\\r\\n) must be present", finalChunkIndex, greaterThanOrEqualTo(0));
        String trailerBlock = raw.substring(finalChunkIndex + "\r\n0\r\n".length());
        assertThat(trailerBlock.toLowerCase(), containsString("x-checksum"));
        assertThat(trailerBlock, containsString("abc123"));
        assertThat(trailerBlock.toLowerCase(), containsString("x-signature"));
        assertThat(trailerBlock, containsString("deadbeef"));
    }

    @Test
    public void shouldNotChangeFramingWhenNoTrailersConfigured() throws Exception {
        // given -- regression: a response with no trailers must keep fixed-length framing
        mockServerClient
            .when(request().withMethod("GET").withPath("/no-trailers"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("plain body")
            );

        // when
        String raw = sendRequestReadRawResponse("/no-trailers");

        // then
        String lower = raw.toLowerCase();
        assertThat(raw, containsString("HTTP/1.1 200"));
        assertThat(lower, containsString("content-length:"));
        assertThat(lower, not(containsString("transfer-encoding: chunked")));
        assertThat(lower, not(containsString("trailer:")));
        assertThat(raw, containsString("plain body"));
    }
}
