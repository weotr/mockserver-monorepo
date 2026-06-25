package org.mockserver.netty.integration.mock;

import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Verifies that a mock response body is written to the wire verbatim, even when the expectation
 * advertises a {@code Content-Encoding} header (issue #2375). MockServer must NOT re-compress the
 * bytes passed to {@code withBody(...)}: a body that is already gzipped must reach the client as
 * exactly those bytes (so unzipping once yields the original, not gzip-of-gzip), and a plaintext
 * body advertised under a gzip {@code Content-Encoding} header must reach the client as plaintext
 * (the deliberate-mismatch scenario).
 */
public class ResponseBodyCompressionIntegrationTest {

    private static byte[] gzip(String content) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    private static String gunzip(byte[] compressed) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = gzip.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Sends a raw GET /test (no Accept-Encoding, so the server has no reason to negotiate encoding)
     * over a one-shot {@code Connection: close} socket and returns the raw response body bytes
     * exactly as they appear on the wire (no client-side decompression). The body is everything
     * after the CRLFCRLF header terminator; the request asks for {@code Connection: close} so the
     * server closes the socket at end of body and we read to EOF.
     */
    private static byte[] rawWireResponseBody(int port) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            String head = "GET /test HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Connection: close\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) {
                responseBuffer.write(buf, 0, n);
            }
            byte[] all = responseBuffer.toByteArray();
            int bodyStart = indexOfHeaderTerminator(all);
            assertThat("response must contain a CRLFCRLF header terminator", bodyStart >= 0, is(true));
            return Arrays.copyOfRange(all, bodyStart, all.length);
        }
    }

    private static int indexOfHeaderTerminator(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    @Test
    public void shouldSendAlreadyGzippedBodyVerbatimUnderGzipContentEncodingHeader() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);
        try {
            byte[] gzippedTest = gzip("test");
            mockServer
                .when(request().withMethod("GET").withPath("/test"))
                .respond(
                    response()
                        .withHeader(header("Content-Encoding", "gzip"))
                        .withBody(binary(gzippedTest))
                );

            byte[] wireBody = rawWireResponseBody(mockServer.getLocalPort());

            // the wire body must be the supplied gzipped bytes verbatim — NOT gzip(gzip("test"))
            assertThat(wireBody, is(gzippedTest));
            // unzipping the wire body ONCE yields "test" (proving it was not double-compressed)
            assertThat(gunzip(wireBody), is("test"));
        } finally {
            stopQuietly(mockServer);
        }
    }

    @Test
    public void shouldSendPlaintextBodyVerbatimUnderGzipContentEncodingHeaderForMismatchScenario() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);
        try {
            // deliberate mismatch: advertise gzip but return plaintext bytes
            byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);
            mockServer
                .when(request().withMethod("GET").withPath("/test"))
                .respond(
                    response()
                        .withHeader(header("Content-Encoding", "gzip"))
                        .withBody(binary(plaintext))
                );

            byte[] wireBody = rawWireResponseBody(mockServer.getLocalPort());

            // the plaintext body is sent verbatim, NOT re-compressed to honour the gzip header
            assertThat(wireBody, is(plaintext));
        } finally {
            stopQuietly(mockServer);
        }
    }
}
