package org.mockserver.client;

import org.junit.Test;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.socket.PortFactory;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class MockServerClientErrorsTest {

    @Test
    public void shouldHandleSocketErrorForReset() {
        // given
        int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("localhost", freePort);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, mockServerClient::reset);

        // then
        // localhost may resolve to 127.0.0.1 or ::1 depending on the host's
        // name resolution, so assert the socket prefix + port rather than a
        // hardcoded IPv4 literal (the old equalTo("...127.0.0.1:port") flaked
        // whenever localhost resolved to IPv6).
        assertThat(clientException.getMessage(), allOf(
            startsWith("Unable to connect to socket localhost/"),
            endsWith(":" + freePort)));
    }

    @Test
    public void shouldHandleSocketErrorForClear() {
        // given
        int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("localhost", freePort);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, () -> mockServerClient.clear(request()));

        // then
        // localhost may resolve to 127.0.0.1 or ::1 depending on the host's
        // name resolution, so assert the socket prefix + port rather than a
        // hardcoded IPv4 literal (the old equalTo("...127.0.0.1:port") flaked
        // whenever localhost resolved to IPv6).
        assertThat(clientException.getMessage(), allOf(
            startsWith("Unable to connect to socket localhost/"),
            endsWith(":" + freePort)));
    }

    @Test
    public void shouldHandleSocketErrorForExpectation() {
        // given
        int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("localhost", freePort);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, () -> mockServerClient.when(request()).respond(response()));

        // then
        // localhost may resolve to 127.0.0.1 or ::1 depending on the host's
        // name resolution, so assert the socket prefix + port rather than a
        // hardcoded IPv4 literal (the old equalTo("...127.0.0.1:port") flaked
        // whenever localhost resolved to IPv6).
        assertThat(clientException.getMessage(), allOf(
            startsWith("Unable to connect to socket localhost/"),
            endsWith(":" + freePort)));
    }

}
