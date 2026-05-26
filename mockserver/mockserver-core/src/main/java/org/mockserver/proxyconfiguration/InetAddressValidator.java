package org.mockserver.proxyconfiguration;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Validates that the destination host of a forward or proxy action is not a
 * loopback, link-local, RFC 1918 private, or cloud metadata address. This
 * blocks server-side request forgery (SSRF) where an attacker registers an
 * expectation that forwards through MockServer to internal infrastructure.
 * <p>
 * Validation is opt-in via {@code mockserver.forwardProxyBlockPrivateNetworks}
 * (default false) because MockServer is most commonly used to mock services
 * running on localhost, Docker bridge networks, or Kubernetes service IPs.
 */
public final class InetAddressValidator {

    // RFC 5735 / 6890 IPv4 metadata addresses used by AWS, GCP, Azure, Oracle Cloud
    private static final String AWS_GCP_AZURE_IPV4_METADATA = "169.254.169.254";
    // RFC 6052 IPv4-mapped IPv6 for the same address
    private static final String AWS_IPV6_METADATA = "fd00:ec2::254";

    private InetAddressValidator() {
    }

    /**
     * Validate a forward target. No-op if the feature is disabled. Throws
     * IllegalArgumentException when the host is unresolvable or resolves to a
     * blocked address range.
     *
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @param host          target host (may be a name or literal address)
     */
    public static void validateForwardTarget(Configuration configuration, String host) {
        boolean enabled = configuration != null
            ? Boolean.TRUE.equals(configuration.forwardProxyBlockPrivateNetworks())
            : ConfigurationProperties.forwardProxyBlockPrivateNetworks();
        if (!enabled) {
            return;
        }
        if (isBlank(host)) {
            return;
        }
        String trimmed = stripBrackets(host);
        InetAddress address;
        try {
            address = InetAddress.getByName(trimmed);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Forward target host \"" + host + "\" could not be resolved", e);
        }
        rejectIfBlocked(host, address);
    }

    private static void rejectIfBlocked(String requestedHost, InetAddress address) {
        String ip = address.getHostAddress();
        if (AWS_GCP_AZURE_IPV4_METADATA.equals(ip) || AWS_IPV6_METADATA.equalsIgnoreCase(ip)) {
            throw new IllegalArgumentException(
                "Forward to cloud metadata endpoint blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isLoopbackAddress()) {
            throw new IllegalArgumentException(
                "Forward to loopback address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isLinkLocalAddress()) {
            throw new IllegalArgumentException(
                "Forward to link-local address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isSiteLocalAddress() || isIpv6UniqueLocal(address)) {
            // Java's isSiteLocalAddress only covers RFC 1918 IPv4 and deprecated fec0::/10 IPv6.
            // Cover the RFC 4193 unique-local IPv6 range (fc00::/7) explicitly so Docker / Kubernetes
            // / Tailscale ULA addresses can't bypass the SSRF policy on IPv6-enabled hosts.
            throw new IllegalArgumentException(
                "Forward to private network blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isAnyLocalAddress()) {
            throw new IllegalArgumentException(
                "Forward to wildcard address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
    }

    private static boolean isIpv6UniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
