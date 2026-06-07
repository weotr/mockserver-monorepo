package org.mockserver.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration tests that verify the Netty epoll transport is activated at
 * runtime when running on Linux with the native library present.
 * <p>
 * These tests are <b>gated</b> by {@code Assume.assumeTrue(Epoll.isAvailable())}
 * so they skip cleanly on macOS/Windows (where the native .so cannot load) and
 * only execute on Linux CI where the {@code netty-transport-native-epoll}
 * runtime-scoped JARs provide the native library.
 * <p>
 * On Linux CI the full existing integration-test suite also exercises the epoll
 * transport implicitly because {@code useNativeTransport} defaults to
 * {@code true} and the native epoll JARs are on the test classpath.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup/EpollEventLoopGroup deprecation in Netty 4.2
public class EpollTransportIntegrationTest {

    private ClientAndServer mockServer;

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    // ---- Priority 1a: epoll server channel type ----

    @Test
    public void shouldUseEpollServerChannelWhenNativeTransportEnabled() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given - start server with useNativeTransport=true (the default, set explicitly)
        Configuration config = configuration().useNativeTransport(true);
        mockServer = new ClientAndServer(config, 0);

        // when - obtain the bound server channel via reflection
        Channel serverChannel = getFirstServerChannel(mockServer);

        // then - the server channel must be epoll
        assertThat("server channel should be EpollServerSocketChannel",
            serverChannel, is(instanceOf(EpollServerSocketChannel.class)));
    }

    // ---- Priority 1a (supplement): boss/worker event loop group type ----

    @Test
    public void shouldUseEpollEventLoopGroupWhenNativeTransportEnabled() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given
        Configuration config = configuration().useNativeTransport(true);
        mockServer = new ClientAndServer(config, 0);

        // when - obtain the event loop groups via reflection on LifeCycle
        MockServer server = getMockServerInstance(mockServer);
        EventLoopGroup bossGroup = getFieldValue(server, "bossGroup", EventLoopGroup.class);
        EventLoopGroup workerGroup = getFieldValue(server, "workerGroup", EventLoopGroup.class);

        // then
        assertThat("boss group should be EpollEventLoopGroup",
            bossGroup, is(instanceOf(EpollEventLoopGroup.class)));
        assertThat("worker group should be EpollEventLoopGroup",
            workerGroup, is(instanceOf(EpollEventLoopGroup.class)));
    }

    // ---- Priority 1b: HTTP round-trip proving epoll serves correctly ----

    @Test
    public void shouldServeExpectationOverEpollTransport() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given - start server with epoll
        Configuration config = configuration().useNativeTransport(true);
        mockServer = new ClientAndServer(config, 0);

        mockServer.when(
            request().withPath("/epoll-test")
        ).respond(
            response()
                .withStatusCode(200)
                .withBody("epoll-ok")
        );

        // when - issue a request using JDK HttpClient
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + mockServer.getLocalPort() + "/epoll-test"))
            .GET()
            .build();
        HttpResponse<String> httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        // then
        assertThat("response status code", httpResponse.statusCode(), is(200));
        assertThat("response body", httpResponse.body(), is("epoll-ok"));
    }

    // ---- Priority 1 (opt-out): NIO forced with useNativeTransport=false on Linux ----

    @Test
    public void shouldUseNioServerChannelWhenNativeTransportDisabledOnLinux() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given - start server with useNativeTransport=false (opt-out)
        Configuration config = configuration().useNativeTransport(false);
        mockServer = new ClientAndServer(config, 0);

        // when
        Channel serverChannel = getFirstServerChannel(mockServer);

        // then - NIO should be used even on Linux
        assertThat("server channel should be NioServerSocketChannel when native transport disabled",
            serverChannel, is(instanceOf(NioServerSocketChannel.class)));
    }

    @Test
    public void shouldUseNioEventLoopGroupWhenNativeTransportDisabledOnLinux() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given
        Configuration config = configuration().useNativeTransport(false);
        mockServer = new ClientAndServer(config, 0);

        // when
        MockServer server = getMockServerInstance(mockServer);
        EventLoopGroup bossGroup = getFieldValue(server, "bossGroup", EventLoopGroup.class);
        EventLoopGroup workerGroup = getFieldValue(server, "workerGroup", EventLoopGroup.class);

        // then
        assertThat("boss group should be NioEventLoopGroup when native transport disabled",
            bossGroup, is(instanceOf(NioEventLoopGroup.class)));
        assertThat("worker group should be NioEventLoopGroup when native transport disabled",
            workerGroup, is(instanceOf(NioEventLoopGroup.class)));
    }

    @Test
    public void shouldServeExpectationOverNioWhenNativeTransportDisabledOnLinux() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given - start server with NIO forced
        Configuration config = configuration().useNativeTransport(false);
        mockServer = new ClientAndServer(config, 0);

        mockServer.when(
            request().withPath("/nio-test")
        ).respond(
            response()
                .withStatusCode(200)
                .withBody("nio-ok")
        );

        // when
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + mockServer.getLocalPort() + "/nio-test"))
            .GET()
            .build();
        HttpResponse<String> httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        // then
        assertThat("response status code", httpResponse.statusCode(), is(200));
        assertThat("response body", httpResponse.body(), is("nio-ok"));
    }

    // ---- Priority 2: DNS datagram channel with epoll ----

    @Test
    public void shouldBootWithDnsEnabledAndNativeTransport() throws Exception {
        Assume.assumeTrue("requires Linux epoll", Epoll.isAvailable());

        // given - start server with dnsEnabled=true AND useNativeTransport=true
        Configuration config = configuration()
            .useNativeTransport(true)
            .dnsEnabled(true);
        mockServer = new ClientAndServer(config, 0);

        // then - the server booted without error
        assertThat("server should be running",
            mockServer.isRunning(), is(true));

        // and - the DNS datagram channel should use epoll
        MockServer server = getMockServerInstance(mockServer);
        Channel dnsChannel = getFieldValue(server, "dnsChannel", Channel.class);
        assertThat("DNS datagram channel should be EpollDatagramChannel",
            dnsChannel, is(instanceOf(EpollDatagramChannel.class)));
    }

    // ---- helpers ----

    /**
     * Extracts the first bound server {@link Channel} from a {@link ClientAndServer}
     * by reflectively accessing the private {@code serverChannelFutures} list in
     * {@link org.mockserver.lifecycle.LifeCycle}.
     * <p>
     * This is test-only reflection -- the alternative (widening the public API with
     * a test accessor) was judged worse for a stable public class.
     */
    @SuppressWarnings("unchecked")
    private Channel getFirstServerChannel(ClientAndServer clientAndServer) throws Exception {
        MockServer server = getMockServerInstance(clientAndServer);
        List<Future<Channel>> futures = getFieldValue(server, "serverChannelFutures", List.class);
        if (futures == null || futures.isEmpty()) {
            throw new IllegalStateException("no server channel futures found");
        }
        return futures.get(0).get();
    }

    /**
     * Extracts the private {@code mockServer} field from a {@link ClientAndServer}.
     */
    private MockServer getMockServerInstance(ClientAndServer clientAndServer) throws Exception {
        return getFieldValue(clientAndServer, "mockServer", MockServer.class);
    }

    /**
     * Reflectively gets a field value, searching the class hierarchy.
     */
    @SuppressWarnings("unchecked")
    private <T> T getFieldValue(Object target, String fieldName, Class<T> type) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in class hierarchy of " + target.getClass().getName());
    }
}
