package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.dns.DnsIntentRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DnsIntentOriginalDestinationResolverTest {

    private final DnsIntentOriginalDestinationResolver resolver = new DnsIntentOriginalDestinationResolver();
    private final Channel mockChannel = mock(Channel.class);

    @Before
    public void setUp() {
        DnsIntentRegistry.getInstance().clear();
    }

    @After
    public void tearDown() {
        DnsIntentRegistry.getInstance().clear();
    }

    @Test
    public void shouldResolveHostnameFromDnsIntent() throws UnknownHostException {
        // given — DNS intent registry has a mapping
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        DnsIntentRegistry.getInstance().record(ip, "api.example.com");
        when(mockChannel.localAddress()).thenReturn(new InetSocketAddress(ip, 8080));

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then — returns an unresolved named address
        assertThat(result, is(InetSocketAddress.createUnresolved("api.example.com", 8080)));
        assertThat(result.isUnresolved(), is(true));
        assertThat(result.getHostString(), is("api.example.com"));
        assertThat(result.getPort(), is(8080));
    }

    @Test
    public void shouldReturnNullWhenNoRegistryEntry() throws UnknownHostException {
        // given — no mapping for this IP
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 99});
        when(mockChannel.localAddress()).thenReturn(new InetSocketAddress(ip, 443));

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullWhenLocalAddressNotInetSocketAddress() {
        // given — localAddress is not an InetSocketAddress (e.g. Unix domain socket)
        when(mockChannel.localAddress()).thenReturn(null);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldResolveIPv6Address() throws UnknownHostException {
        // given
        InetAddress ipv6 = InetAddress.getByAddress(new byte[]{
            0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        });
        DnsIntentRegistry.getInstance().record(ipv6, "ipv6.example.com");
        when(mockChannel.localAddress()).thenReturn(new InetSocketAddress(ipv6, 9090));

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then
        assertThat(result, is(InetSocketAddress.createUnresolved("ipv6.example.com", 9090)));
        assertThat(result.isUnresolved(), is(true));
    }
}
