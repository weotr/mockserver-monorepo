package org.mockserver.mock;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MockModeTest {

    @Test
    public void simulateDoesNotProxyUnmatchedRequests() {
        assertFalse(MockMode.SIMULATE.proxyUnmatchedRequests());
    }

    @Test
    public void spyAndCaptureProxyUnmatchedRequests() {
        assertTrue(MockMode.SPY.proxyUnmatchedRequests());
        assertTrue(MockMode.CAPTURE.proxyUnmatchedRequests());
    }

    @Test
    public void derivesModeFromProxyFlag() {
        assertEquals(MockMode.SPY, MockMode.fromProxyFlag(true));
        assertEquals(MockMode.SIMULATE, MockMode.fromProxyFlag(false));
    }

    @Test
    public void parsesModeCaseInsensitively() {
        assertEquals(MockMode.SPY, MockMode.parse("spy"));
        assertEquals(MockMode.CAPTURE, MockMode.parse("  Capture "));
        assertEquals(MockMode.SIMULATE, MockMode.parse("SIMULATE"));
    }

    @Test
    public void rejectsBlankMode() {
        assertThrows(IllegalArgumentException.class, () -> MockMode.parse("  "));
    }

    @Test
    public void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> MockMode.parse("turbo"));
    }
}
