package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

public class CompositeOriginalDestinationResolverTest {

    private final Channel mockChannel = mock(Channel.class);

    @Test
    public void shouldReturnFirstNonNullResult() {
        // given — first strategy returns null, second returns an address
        InetSocketAddress expected = new InetSocketAddress("10.0.0.1", 80);
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> null,
            channel -> expected
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(expected));
    }

    @Test
    public void shouldReturnResultFromFirstStrategy() {
        // given — first strategy returns an address (second should not be called)
        InetSocketAddress first = new InetSocketAddress("10.0.0.1", 80);
        InetSocketAddress second = new InetSocketAddress("10.0.0.2", 443);
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> first,
            channel -> second
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then — first hit wins
        assertThat(result, is(first));
    }

    @Test
    public void shouldSkipUnsupportedOperationException() {
        // given — first throws UnsupportedOperationException, second returns an address
        InetSocketAddress expected = new InetSocketAddress("10.0.0.1", 80);
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> { throw new UnsupportedOperationException("not on this platform"); },
            channel -> expected
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(expected));
    }

    @Test
    public void shouldSkipUnexpectedExceptions() {
        // given — first throws RuntimeException, second returns an address
        InetSocketAddress expected = new InetSocketAddress("10.0.0.1", 80);
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> { throw new RuntimeException("conntrack read failed"); },
            channel -> expected
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(expected));
    }

    @Test
    public void shouldReturnNullWhenAllStrategiesReturnNull() {
        // given — all strategies return null
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> null,
            channel -> null
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullWhenAllStrategiesThrow() {
        // given — all strategies throw
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> { throw new UnsupportedOperationException("not Linux"); },
            channel -> { throw new RuntimeException("failed"); }
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void defaultChainShouldContainThreeStrategies() {
        // when — no-arg overload (backward compatibility, no TPROXY)
        CompositeOriginalDestinationResolver resolver = CompositeOriginalDestinationResolver.defaultChain();

        // then — [SO_ORIGINAL_DST, conntrack, dns-intent]
        assertThat(resolver.strategyCount(), is(3));
    }

    @Test
    public void defaultChainWithConfigShouldContainFourStrategies() {
        // when — configuration-aware overload includes TPROXY
        Configuration config = Configuration.configuration();
        CompositeOriginalDestinationResolver resolver = CompositeOriginalDestinationResolver.defaultChain(config);

        // then — [TPROXY, SO_ORIGINAL_DST, conntrack, dns-intent]
        assertThat(resolver.strategyCount(), is(4));
    }

    @Test
    public void defaultChainShouldDelegateToConntrackResolver() {
        // given — default chain on non-Linux should throw UnsupportedOperationException
        // which gets caught internally and returns null
        CompositeOriginalDestinationResolver resolver = CompositeOriginalDestinationResolver.defaultChain();

        // when — on non-Linux, the conntrack resolver throws UnsupportedOperationException
        // which the composite catches and falls through; result should be null
        // on Linux with no matching conntrack entry, result is also null
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then — either way, no exception escapes
        assertThat(result, is(nullValue()));
    }

    @Test
    public void defaultChainWithConfigShouldReturnNullOnNonLinux() {
        // given — default chain with config on non-Linux
        Configuration config = Configuration.configuration();
        CompositeOriginalDestinationResolver resolver = CompositeOriginalDestinationResolver.defaultChain(config);

        // when — TPROXY not enabled + non-Linux = all return null
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullStrategies() {
        new CompositeOriginalDestinationResolver(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyStrategies() {
        new CompositeOriginalDestinationResolver(Collections.emptyList());
    }

    @Test
    public void shouldReportStrategyCount() {
        // given
        List<TransparentProxyHandler.OriginalDestinationResolver> strategies = Arrays.asList(
            channel -> null,
            channel -> null,
            channel -> null
        );
        CompositeOriginalDestinationResolver resolver = new CompositeOriginalDestinationResolver(strategies);

        // then
        assertThat(resolver.strategyCount(), is(3));
    }
}
