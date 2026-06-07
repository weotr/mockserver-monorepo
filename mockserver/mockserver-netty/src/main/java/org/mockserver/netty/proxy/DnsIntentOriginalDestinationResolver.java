package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.mockserver.mock.dns.DnsIntentRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Resolves the original destination of a transparently intercepted TCP connection
 * by consulting the {@link DnsIntentRegistry} — a record of hostname-to-IP answers
 * that MockServer's DNS server has handed out.
 * <p>
 * <b>When this applies:</b> DNS-steering mode. The client resolved a hostname via
 * MockServer's DNS server, received an IP address (A/AAAA record) that routes to
 * MockServer, and connected by IP. This resolver recovers the intended hostname
 * so that expectation matching and forwarding work by name.
 * <p>
 * <b>Chain position:</b> this resolver runs after the conntrack resolver in the
 * {@link CompositeOriginalDestinationResolver} chain. A real iptables-REDIRECT
 * original destination (from conntrack or SO_ORIGINAL_DST) always wins; this
 * resolver only fills the gap when conntrack returns null (e.g., no iptables rule,
 * or the platform is not Linux).
 * <p>
 * <b>Unresolved address:</b> this resolver intentionally returns an unresolved
 * (named) {@link InetSocketAddress} via {@link InetSocketAddress#createUnresolved}.
 * Downstream forwarding logic resolves the hostname itself, and MockServer's
 * existing loop-prevention header guards against a DNS-to-self loop.
 *
 * @see DnsIntentRegistry
 * @see CompositeOriginalDestinationResolver
 */
public class DnsIntentOriginalDestinationResolver implements TransparentProxyHandler.OriginalDestinationResolver {

    @Override
    public InetSocketAddress resolve(Channel channel) {
        java.net.SocketAddress localAddr = channel.localAddress();
        if (!(localAddr instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress localSocketAddress = (InetSocketAddress) localAddr;
        InetAddress ip = localSocketAddress.getAddress();
        if (ip == null) {
            return null;
        }
        String hostname = DnsIntentRegistry.getInstance().recover(ip);
        if (hostname == null) {
            return null;
        }
        return InetSocketAddress.createUnresolved(hostname, localSocketAddress.getPort());
    }
}
