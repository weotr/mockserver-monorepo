package org.mockserver.netty.proxy;

import io.netty.bootstrap.ServerBootstrap;
import org.mockserver.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the {@code IP_TRANSPARENT} socket option on the server bootstrap when
 * TPROXY mode is enabled.
 * <p>
 * The {@code IP_TRANSPARENT} socket option (Linux {@code SOL_IP = 0, IP_TRANSPARENT = 19})
 * allows the listener socket to accept connections destined for any IP address, not
 * just the local addresses of the host. This is required for the TPROXY iptables
 * target to work: the kernel redirects traffic to the listener socket while
 * preserving the original destination as the socket's local address.
 * <p>
 * On Netty epoll transport, this is exposed as
 * {@code io.netty.channel.epoll.EpollChannelOption.IP_TRANSPARENT}. On NIO transport
 * or non-Linux platforms, this method is a no-op (logs a debug message).
 * <p>
 * <b>Usage:</b> call {@link #applyIfEnabled(ServerBootstrap, Configuration)} after
 * constructing the {@link ServerBootstrap} and before binding. This is idempotent.
 * <p>
 * <b>Requires:</b> {@code CAP_NET_ADMIN} on the process. Without it, the
 * {@code setsockopt(IP_TRANSPARENT)} call will fail with {@code EPERM} and the
 * Netty channel bind will fail. This is expected in non-TPROXY deployments.
 *
 * @see TproxyOriginalDestinationResolver
 */
public final class MockServerIpTransparentHelper {

    private static final Logger LOG = LoggerFactory.getLogger(MockServerIpTransparentHelper.class);

    private MockServerIpTransparentHelper() {
        // utility class
    }

    /**
     * Applies the {@code IP_TRANSPARENT} channel option to the server bootstrap
     * if TPROXY mode is enabled in configuration and the epoll transport is available.
     *
     * @param bootstrap     the server bootstrap to configure
     * @param configuration the MockServer configuration
     * @return {@code true} if the option was applied, {@code false} otherwise
     */
    public static boolean applyIfEnabled(ServerBootstrap bootstrap, Configuration configuration) {
        if (!Boolean.TRUE.equals(configuration.transparentProxyTproxy())) {
            return false;
        }

        if (!Boolean.TRUE.equals(configuration.transparentProxyEnabled())) {
            LOG.warn("transparentProxyTproxy=true requires transparentProxyEnabled=true; "
                + "IP_TRANSPARENT not applied");
            return false;
        }

        try {
            return applyEpollIpTransparent(bootstrap);
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            LOG.debug("epoll transport not available for IP_TRANSPARENT: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Applies the epoll IP_TRANSPARENT option. Isolated to avoid class-loading
     * the epoll classes when they are not on the classpath.
     */
    private static boolean applyEpollIpTransparent(ServerBootstrap bootstrap) {
        if (!io.netty.channel.epoll.Epoll.isAvailable()) {
            LOG.debug("epoll not available; IP_TRANSPARENT not applied");
            return false;
        }

        // Set IP_TRANSPARENT on the server (parent) channel so the kernel allows
        // binding to non-local addresses. Child channels inherit this.
        bootstrap.option(io.netty.channel.epoll.EpollChannelOption.IP_TRANSPARENT, true);
        // Also set on child channels for socket-level transparency
        bootstrap.childOption(io.netty.channel.epoll.EpollChannelOption.IP_TRANSPARENT, true);
        LOG.info("IP_TRANSPARENT socket option applied (TPROXY mode enabled)");
        return true;
    }
}
