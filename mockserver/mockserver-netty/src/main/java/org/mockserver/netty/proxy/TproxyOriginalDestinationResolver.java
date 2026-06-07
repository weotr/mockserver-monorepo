package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.mockserver.configuration.Configuration;

import java.net.InetSocketAddress;

/**
 * Resolves the original destination of a transparently intercepted TCP connection
 * using the TPROXY (IP_TRANSPARENT) mechanism.
 * <p>
 * With TPROXY iptables rules (as opposed to REDIRECT), the kernel preserves the
 * original destination address as the socket's <b>local address</b>. The listener
 * socket must be bound with the {@code IP_TRANSPARENT} socket option so the kernel
 * allows binding to non-local addresses, and the iptables rules must use the
 * {@code -j TPROXY} target instead of {@code -j REDIRECT}.
 * <p>
 * <b>Resolution is trivial:</b> {@code channel.localAddress()} returns the original
 * destination directly (the pre-TPROXY destination). No conntrack table lookup or
 * {@code getsockopt(SO_ORIGINAL_DST)} is needed.
 * <p>
 * <b>Requirements:</b>
 * <ul>
 *   <li>Linux with Netty epoll transport</li>
 *   <li>{@code CAP_NET_ADMIN} capability (for {@code IP_TRANSPARENT} setsockopt)</li>
 *   <li>TPROXY iptables rules instead of REDIRECT:
 *       <pre>{@code
 * iptables -t mangle -A PREROUTING -p tcp --dport <target-port> \
 *   -j TPROXY --tproxy-mark 0x1/0x1 --on-port <mockserver-port>
 * ip rule add fwmark 1 lookup 100
 * ip route add local 0.0.0.0/0 dev lo table 100
 *       }</pre>
 *   </li>
 *   <li>The {@code IP_TRANSPARENT} socket option set on the listener socket
 *       (wired in {@link MockServerIpTransparentHelper} or via
 *       {@code EpollChannelOption.IP_TRANSPARENT})</li>
 *   <li>Configuration flag: {@code mockserver.transparentProxyTproxy=true}</li>
 * </ul>
 * <p>
 * <b>Chain position:</b> in the {@link CompositeOriginalDestinationResolver} default
 * chain, TPROXY is placed <b>first</b> (before SO_ORIGINAL_DST and conntrack). When
 * TPROXY mode is active, the local address is the authoritative original destination;
 * when inactive, this resolver returns {@code null} and the chain falls through to
 * SO_ORIGINAL_DST / conntrack.
 * <p>
 * <b>Difference from REDIRECT:</b> with REDIRECT, the socket's local address is the
 * MockServer listen address (the redirect target), so {@code channel.localAddress()}
 * is useless for original-destination recovery. With TPROXY, the local address IS
 * the original destination.
 *
 * @see CompositeOriginalDestinationResolver
 * @see TransparentProxyHandler
 */
public class TproxyOriginalDestinationResolver implements TransparentProxyHandler.OriginalDestinationResolver {

    private final Configuration configuration;

    /**
     * Creates a TPROXY resolver that checks whether TPROXY mode is active
     * via the given configuration.
     *
     * @param configuration the MockServer configuration (used to read
     *                      {@code transparentProxyTproxy})
     */
    public TproxyOriginalDestinationResolver(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Resolves the original destination by reading the channel's local address.
     * <p>
     * Returns {@code null} when:
     * <ul>
     *   <li>TPROXY mode is not enabled in configuration</li>
     *   <li>The channel's local address is null or not an {@link InetSocketAddress}</li>
     * </ul>
     *
     * @param channel the accepted Netty channel
     * @return the original destination (from the local address), or {@code null}
     */
    @Override
    public InetSocketAddress resolve(Channel channel) {
        if (!Boolean.TRUE.equals(configuration.transparentProxyTproxy())) {
            return null;
        }

        if (channel == null) {
            return null;
        }

        java.net.SocketAddress localAddr = channel.localAddress();
        if (!(localAddr instanceof InetSocketAddress)) {
            return null;
        }

        return (InetSocketAddress) localAddr;
    }
}
