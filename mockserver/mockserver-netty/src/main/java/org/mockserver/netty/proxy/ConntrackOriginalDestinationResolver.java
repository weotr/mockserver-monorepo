package org.mockserver.netty.proxy;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;

/**
 * Resolves the original destination of a transparently intercepted TCP connection
 * by reading the Linux conntrack table ({@code /proc/net/nf_conntrack}).
 * <p>
 * This is the default strategy for transparent proxy mode on Linux. It delegates to
 * {@link SoOriginalDstHelper#getOriginalDestination(Channel)}, which parses the
 * kernel conntrack table to find the pre-{@code iptables -j REDIRECT} destination.
 * <p>
 * On non-Linux platforms, this resolver throws {@link UnsupportedOperationException},
 * which the {@link CompositeOriginalDestinationResolver} catches and continues to the
 * next strategy.
 *
 * @see SoOriginalDstHelper
 * @see CompositeOriginalDestinationResolver
 */
public class ConntrackOriginalDestinationResolver implements TransparentProxyHandler.OriginalDestinationResolver {

    @Override
    public InetSocketAddress resolve(Channel channel) {
        return SoOriginalDstHelper.getOriginalDestination(channel);
    }
}
