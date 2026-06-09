package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.mockserver.testing.integration.mock.AbstractTransportAgnosticSemanticsIntegrationTest;

import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Concrete runner for transport-agnostic matching/priority/TTL/verify/retrieve
 * semantic integration tests.
 * <p>
 * These tests run once against a single Netty transport (rather than
 * per-transport via inheritance) because they exercise expectation-matching
 * semantics that are transport-decode-independent.
 *
 * @see AbstractTransportAgnosticSemanticsIntegrationTest
 */
public class TransportAgnosticSemanticsIntegrationTest extends AbstractTransportAgnosticSemanticsIntegrationTest {

    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerClient = startClientAndServer();
        mockServerPort = mockServerClient.getPort();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Override
    public int getServerPort() {
        return mockServerPort;
    }

}
