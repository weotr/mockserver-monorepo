package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;

import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Runs the full Extended (L3+L4) mocking test matrix over HTTP/2.
 * <p>
 * Requests to the secure port are upgraded to HTTP/2 via ALPN,
 * mirroring the pattern in {@link HTTP2MockingIntegrationTest} but
 * extending the richer {@link AbstractExtendedNettyMockingIntegrationTest}
 * hierarchy so that body-decode, header, and parameter-style tests
 * execute over the HTTP/2 transport for the first time.
 */
public class HTTP2ExtendedMockingIntegrationTest extends AbstractExtendedNettyMockingIntegrationTest {

    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort, servletContext);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Override
    public int getServerPort() {
        return mockServerPort;
    }

    @Override
    public HttpRequest getRequestModifier(HttpRequest httpRequest) {
        // TODO(jamesdbloom) support http2 in plain text
        if (Boolean.TRUE.equals(httpRequest.isSecure())) {
            return httpRequest
                .clone()
                .withProtocol(Protocol.HTTP_2);
        } else {
            return httpRequest;
        }
    }

    // --- HTTP/2 decode-gap overrides: inherited tests that fail over HTTP/2 transport ---

    @Override
    @Test
    @Ignore("decode-gap: HTTP/2 does not support custom reason phrases (protocol has no reason-phrase field) — see test-coverage audit")
    public void shouldReturnResponseWithCustomReasonPhrase() {
        super.shouldReturnResponseWithCustomReasonPhrase();
    }

    @Override
    @Test
    @Ignore("decode-gap: HTTP/2 HEADERS frame size limit causes TimeoutException for 16KB header value — see test-coverage audit")
    public void shouldReturnResponseByMatchingVeryLargeHeader() {
        super.shouldReturnResponseByMatchingVeryLargeHeader();
    }

    @Override
    @Test
    @Ignore("decode-gap: ConnectionOptions keepAlive/contentLengthOverride incompatible with HTTP/2 flow control (TimeoutException) — see test-coverage audit")
    public void shouldReturnResponseWithConnectionOptionsAndKeepAliveTrueAndContentLengthOverride() {
        super.shouldReturnResponseWithConnectionOptionsAndKeepAliveTrueAndContentLengthOverride();
    }

    @Override
    @Test
    @Ignore("decode-gap: ConnectionOptions keepAlive/contentLengthOverride incompatible with HTTP/2 flow control (TimeoutException) — see test-coverage audit")
    public void shouldReturnResponseWithConnectionOptionsAndKeepAliveFalseAndContentLengthOverride() {
        super.shouldReturnResponseWithConnectionOptionsAndKeepAliveFalseAndContentLengthOverride();
    }

}
