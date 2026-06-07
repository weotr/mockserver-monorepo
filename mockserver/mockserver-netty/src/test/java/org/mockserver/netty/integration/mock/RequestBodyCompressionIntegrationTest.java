package org.mockserver.netty.integration.mock;

import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Verifies that compressed (Content-Encoding) request bodies retain their original on-the-wire bytes
 * alongside the decompressed body (issue #2326): a BinaryBody expectation matches against either
 * representation, and HttpRequest#getBodyAsOriginalRawBytes() returns the original compressed bytes
 * (surviving serialization through retrieveRecordedRequests).
 */
public class RequestBodyCompressionIntegrationTest {

    private static byte[] gzip(String content) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    /**
     * Sends a single POST /test with a gzip Content-Encoding and the given (already compressed) body bytes
     * over a one-shot connection, returning the HTTP status code from the response line.
     */
    private static int sendGzippedRequest(int port, byte[] compressedBody) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            String head = "POST /test HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Encoding: gzip\r\n" +
                "Connection: close\r\n" +
                "Content-Length: " + compressedBody.length + "\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.UTF_8));
            output.write(compressedBody);
            output.flush();

            InputStream input = socket.getInputStream();
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) {
                responseBuffer.write(buf, 0, n);
            }
            String response = responseBuffer.toString(StandardCharsets.UTF_8.name());
            // first line: HTTP/1.1 <status> <reason>
            String statusLine = response.split("\r\n", 2)[0];
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @Test
    public void shouldMatchBinaryBodyExpectationAgainstOriginalCompressedBytes() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);
        try {
            byte[] compressedBody = gzip("plain");
            // the expectation targets the ORIGINAL compressed payload
            mockServer.when(request().withMethod("POST").withPath("/test").withBody(binary(compressedBody)))
                .respond(response().withStatusCode(202));

            int statusCode = sendGzippedRequest(mockServer.getLocalPort(), compressedBody);

            // matched against the original compressed bytes
            assertThat(statusCode, is(202));
        } finally {
            stopQuietly(mockServer);
        }
    }

    @Test
    public void shouldMatchBinaryBodyExpectationAgainstDecompressedBytes() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);
        try {
            // the expectation targets the DECOMPRESSED payload
            mockServer.when(request().withMethod("POST").withPath("/test").withBody(binary("plain".getBytes(StandardCharsets.UTF_8))))
                .respond(response().withStatusCode(203));

            int statusCode = sendGzippedRequest(mockServer.getLocalPort(), gzip("plain"));

            // matched against the decompressed bytes
            assertThat(statusCode, is(203));
        } finally {
            stopQuietly(mockServer);
        }
    }

    @Test
    public void shouldExposeOriginalCompressedBytesOnRecordedRequest() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);
        try {
            mockServer.when(request().withMethod("POST").withPath("/test")).respond(response().withStatusCode(200));
            byte[] compressedBody = gzip("plain");

            sendGzippedRequest(mockServer.getLocalPort(), compressedBody);

            HttpRequest[] recorded = mockServer.retrieveRecordedRequests(request().withMethod("POST").withPath("/test"));
            assertThat(recorded.length, is(1));
            // getBodyAsRawBytes() is unchanged — the decompressed content
            assertThat(new String(recorded[0].getBodyAsRawBytes(), StandardCharsets.UTF_8), is("plain"));
            // getBodyAsOriginalRawBytes() returns the original compressed bytes (survived serialization)
            assertThat(recorded[0].getBodyAsOriginalRawBytes(), is(compressedBody));
        } finally {
            stopQuietly(mockServer);
        }
    }
}
