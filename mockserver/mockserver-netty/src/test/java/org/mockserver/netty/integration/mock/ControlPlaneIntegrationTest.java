package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.mockserver.testing.integration.mock.AbstractControlPlaneIntegrationTest;

import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Concrete runner for transport-agnostic control-plane integration tests.
 * <p>
 * These tests run once against a single Netty transport (rather than
 * per-transport via inheritance) because they exercise control-plane API
 * semantics (verify, clear, reset, retrieve, error validation) that are
 * transport-decode-independent.
 *
 * @see AbstractControlPlaneIntegrationTest
 */
public class ControlPlaneIntegrationTest extends AbstractControlPlaneIntegrationTest {

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
