package org.mockserver.httpclient;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit tests for the forward/proxy client's TCP keepalive bootstrap hardening
 * ({@link NettyHttpClient#applyForwardSocketKeepAlive}). These assert the deterministic, platform
 * independent part of the behaviour: that SO_KEEPALIVE is set on the bootstrap when enabled and not
 * when disabled, by inspecting the bootstrap's public option map ({@code bootstrap.config().options()}).
 * <p>
 * Live keepalive probe timing is not asserted (not practical in a unit test) and the epoll timer
 * options are only set on the native epoll transport; this test runs against a NIO group, where (per
 * the design) only SO_KEEPALIVE is applied and the epoll timer options are intentionally absent. The
 * config getters that drive the epoll values are covered in {@code ConfigurationTest}.
 */
public class NettyHttpClientKeepAliveTest {

    private static EventLoopGroup nioGroup;
    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @BeforeClass
    public static void startGroup() {
        nioGroup = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopGroup() {
        nioGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private Bootstrap nioBootstrap() {
        return new Bootstrap().group(nioGroup).channel(NioSocketChannel.class);
    }

    @Test
    public void shouldSetSoKeepAliveOnBootstrapWhenEnabled() {
        Bootstrap bootstrap = nioBootstrap();

        NettyHttpClient.applyForwardSocketKeepAlive(bootstrap, nioGroup, true, 60, 15, 4, mockServerLogger);

        Map<ChannelOption<?>, Object> options = bootstrap.config().options();
        assertThat("SO_KEEPALIVE is enabled on the forward client bootstrap when keepalive is on",
            options.get(ChannelOption.SO_KEEPALIVE), is(notNullValue()));
        assertThat(options.get(ChannelOption.SO_KEEPALIVE), is(Boolean.TRUE));
    }

    @Test
    public void shouldNotSetAnyKeepAliveOptionWhenDisabled() {
        Bootstrap bootstrap = nioBootstrap();

        NettyHttpClient.applyForwardSocketKeepAlive(bootstrap, nioGroup, false, 60, 15, 4, mockServerLogger);

        // disabled => the historical behaviour (no SO_KEEPALIVE set at all) is exactly preserved
        assertThat("no SO_KEEPALIVE option is set when keepalive is disabled",
            bootstrap.config().options().get(ChannelOption.SO_KEEPALIVE), is(nullValue()));
    }

    @Test
    public void shouldNotSetEpollTimerOptionsOnNioTransport() {
        Bootstrap bootstrap = nioBootstrap();

        // on a NIO group the epoll timer options must NOT be applied (only SO_KEEPALIVE) -- no epoll
        // ChannelOption keys should appear in the option map
        NettyHttpClient.applyForwardSocketKeepAlive(bootstrap, nioGroup, true, 60, 15, 4, mockServerLogger);

        boolean anyEpollOption = bootstrap.config().options().keySet().stream()
            .anyMatch(option -> option.name().startsWith("TCP_KEEP"));
        assertThat("epoll TCP_KEEP* timer options are not applied on the NIO transport", anyEpollOption, is(false));
    }
}
