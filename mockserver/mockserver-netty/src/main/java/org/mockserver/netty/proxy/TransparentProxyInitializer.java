package org.mockserver.netty.proxy;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

/**
 * Utility for transparent HTTP proxy mode — provides Host-header parsing helpers
 * and an {@link #isEnabled()} configuration check.
 * <p>
 * <b>Production path:</b> The actual original-destination resolution and
 * channel attribute setup is performed by {@link TransparentProxyHandler}, which
 * fires on {@code channelActive} and writes the {@code REMOTE_SOCKET} attribute
 * consumed by the forward path. This class provides supplementary Host-header
 * parsing utilities.
 */
public class TransparentProxyInitializer {

    private final Configuration configuration;

    public TransparentProxyInitializer(Configuration configuration, MockServerLogger logger) {
        this.configuration = configuration;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(configuration.transparentProxyEnabled());
    }

    /**
     * Returns the forwarding host for a transparent proxy request.
     * In transparent proxy mode, the Host header is the target.
     *
     * @param hostHeader the Host header value from the incoming request
     * @return the target hostname, or null if the header is empty
     */
    public String resolveTargetHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return null;
        }
        // Handle IPv6 addresses in brackets like [::1]:8080
        if (hostHeader.startsWith("[")) {
            int closeBracket = hostHeader.indexOf(']');
            if (closeBracket > 0) {
                return hostHeader.substring(0, closeBracket + 1);
            }
            return hostHeader;
        }
        // Strip port if present
        int colonIdx = hostHeader.lastIndexOf(':');
        if (colonIdx > 0) {
            return hostHeader.substring(0, colonIdx);
        }
        return hostHeader;
    }

    /**
     * Returns the forwarding port for a transparent proxy request.
     *
     * @param hostHeader the Host header value from the incoming request
     * @param secure     whether the connection is TLS
     * @return the target port
     */
    public int resolveTargetPort(String hostHeader, boolean secure) {
        if (hostHeader == null) {
            return secure ? 443 : 80;
        }
        // Handle IPv6 addresses in brackets like [::1]:8080
        if (hostHeader.startsWith("[")) {
            int closeBracket = hostHeader.indexOf(']');
            if (closeBracket > 0 && closeBracket + 1 < hostHeader.length() && hostHeader.charAt(closeBracket + 1) == ':') {
                try {
                    return Integer.parseInt(hostHeader.substring(closeBracket + 2));
                } catch (NumberFormatException e) {
                    // fall through to default
                }
            }
            return secure ? 443 : 80;
        }
        int colonIdx = hostHeader.lastIndexOf(':');
        if (colonIdx > 0) {
            try {
                return Integer.parseInt(hostHeader.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return secure ? 443 : 80;
    }
}
