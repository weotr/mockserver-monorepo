package org.mockserver.socket;

import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class PortFactoryTest {

    @Test
    public void shouldFindFreePort() throws IOException {
        // when
        int freePort = PortFactory.findFreePort();

        // then
        ServerSocket serverSocket = new ServerSocket(freePort);
        assertThat(serverSocket.isBound(), is(true));
        serverSocket.close();
    }

    @Test
    public void shouldFindMultipleFreePorts() throws IOException {
        // when
        int[] freePorts = PortFactory.findFreePorts(3);

        // then
        assertThat(freePorts.length, is(3));
        for (int freePort : freePorts) {
            ServerSocket serverSocket = new ServerSocket(freePort);
            assertThat(serverSocket.isBound(), is(true));
            serverSocket.close();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForZeroCount() {
        PortFactory.findFreePorts(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForNegativeCount() {
        PortFactory.findFreePorts(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForExcessiveCount() {
        PortFactory.findFreePorts(1001);
    }

    @Test
    public void shouldFindMultipleFreePortsWithDistinctValues() throws IOException {
        // when
        int[] freePorts = PortFactory.findFreePorts(5);

        // then
        assertThat(freePorts.length, is(5));
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (int port : freePorts) {
            unique.add(port);
        }
        assertThat(unique.size(), is(5));
    }

}
