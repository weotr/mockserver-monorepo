package org.mockserver.netty.proxy;

import io.netty.channel.Channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Reads the original destination of an intercepted TCP connection on Linux.
 * <p>
 * When iptables {@code -j REDIRECT} rewrites a packet's destination, the kernel
 * records the original destination in the conntrack table. The standard way to
 * retrieve it is {@code getsockopt(fd, SOL_IP, SO_ORIGINAL_DST, ...)} but Netty
 * does not expose this socket option (not even via {@code EpollChannelOption}).
 * <p>
 * This helper uses a JNI-free fallback: it parses {@code /proc/net/nf_conntrack}
 * (or the legacy {@code /proc/net/ip_conntrack}) to look up the original
 * destination by matching the connection's local and remote addresses. This
 * approach is used by several production transparent proxies and works on
 * standard Linux kernels with {@code nf_conntrack} loaded.
 * <p>
 * <b>Limitations (honest):</b>
 * <ul>
 *   <li>Linux only. On other OSes, {@link #getOriginalDestination} throws
 *       {@link UnsupportedOperationException}.</li>
 *   <li>Requires the {@code nf_conntrack} (or {@code ip_conntrack}) kernel
 *       module to be loaded and readable by the MockServer process.</li>
 *   <li>The conntrack lookup is O(n) where n = number of tracked connections.
 *       For high-connection-rate deployments consider the JNI-based
 *       {@code SO_ORIGINAL_DST} approach (not yet implemented).</li>
 *   <li>IPv6 original-destination lookup is supported but requires
 *       {@code /proc/net/nf_conntrack} (not the legacy {@code ip_conntrack}).</li>
 *   <li>If the conntrack entry has been flushed or the file is unreadable,
 *       returns {@code null} (caller falls back to Host header).</li>
 * </ul>
 */
public class SoOriginalDstHelper {

    private static final boolean IS_LINUX;

    static {
        String osName = System.getProperty("os.name", "");
        IS_LINUX = osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    /**
     * Path provider for the conntrack proc file. Package-private so tests can override.
     */
    interface ConntrackPathProvider {
        Path nfConntrackPath();

        Path ipConntrackPath();
    }

    private static final ConntrackPathProvider DEFAULT_PATH_PROVIDER = new ConntrackPathProvider() {
        @Override
        public Path nfConntrackPath() {
            return Paths.get("/proc/net/nf_conntrack");
        }

        @Override
        public Path ipConntrackPath() {
            return Paths.get("/proc/net/ip_conntrack");
        }
    };

    // Package-private static test seam. Not parallel-safe: tests that swap this
    // field must run sequentially (Netty proxy tests are single-threaded in Surefire).
    static ConntrackPathProvider pathProvider = DEFAULT_PATH_PROVIDER;

    private SoOriginalDstHelper() {
    }

    /**
     * Returns {@code true} if the current OS is Linux, which is the only
     * platform where SO_ORIGINAL_DST / conntrack-based original destination
     * resolution is supported.
     */
    public static boolean isSupported() {
        return IS_LINUX;
    }

    /**
     * Attempts to read the original destination of the connection associated
     * with the given Netty channel.
     *
     * @param channel the accepted Netty channel
     * @return the original destination address, or {@code null} if it could
     * not be determined (caller should fall back to Host header)
     * @throws UnsupportedOperationException on non-Linux platforms
     */
    public static InetSocketAddress getOriginalDestination(Channel channel) {
        if (!IS_LINUX) {
            throw new UnsupportedOperationException(
                "SO_ORIGINAL_DST requires Linux with conntrack; current OS: "
                    + System.getProperty("os.name", "unknown")
            );
        }

        if (channel == null) {
            return null;
        }

        // The channel's local address after iptables REDIRECT is the redirected
        // address (i.e., MockServer's listen address). The remote address is the
        // client's address. We need to find the conntrack entry that maps:
        //   src=<remoteAddr> dst=<original-dst> ... sport=<remotePort> dport=<original-port>
        // and also has the reply tuple matching our local address.
        InetSocketAddress localAddr = (InetSocketAddress) channel.localAddress();
        InetSocketAddress remoteAddr = (InetSocketAddress) channel.remoteAddress();

        if (localAddr == null || remoteAddr == null) {
            return null;
        }

        return lookupConntrack(remoteAddr, localAddr);
    }

    /**
     * Looks up the original destination in the Linux conntrack table by
     * parsing /proc/net/nf_conntrack (or /proc/net/ip_conntrack as fallback).
     * <p>
     * A conntrack line for a REDIRECTed connection looks like:
     * <pre>
     * ipv4  2 tcp  6 431999 ESTABLISHED src=10.0.0.2 dst=93.184.216.34 sport=45678 dport=80
     *     src=127.0.0.1 dst=10.0.0.2 sport=1080 dport=45678 ...
     * </pre>
     * The first tuple is the original connection (before REDIRECT). The second
     * tuple is the reply direction. We match on:
     * <ul>
     *   <li>First tuple: {@code src} = client IP, {@code sport} = client port</li>
     *   <li>Second tuple: {@code src} = our local IP, {@code sport} = our local port</li>
     * </ul>
     * And extract the first tuple's {@code dst} + {@code dport} as the original destination.
     *
     * @param remoteAddr the client's address (channel.remoteAddress())
     * @param localAddr  the server's local address (channel.localAddress())
     * @return the original destination, or null if not found
     */
    static InetSocketAddress lookupConntrack(InetSocketAddress remoteAddr, InetSocketAddress localAddr) {
        String clientIp = normalizeIp(remoteAddr.getAddress().getHostAddress());
        int clientPort = remoteAddr.getPort();
        String localIp = normalizeIp(localAddr.getAddress().getHostAddress());
        int localPort = localAddr.getPort();

        // Try nf_conntrack first, then legacy ip_conntrack
        Path conntrackPath = pathProvider.nfConntrackPath();
        if (!Files.isReadable(conntrackPath)) {
            conntrackPath = pathProvider.ipConntrackPath();
            if (!Files.isReadable(conntrackPath)) {
                return null;
            }
        }

        // Cap the scan at MAX_CONNTRACK_LINES to bound CPU/memory cost on busy hosts
        // with huge conntrack tables. If we exceed the limit, fall back to Host header.
        final int MAX_CONNTRACK_LINES = 200_000;
        try (BufferedReader reader = Files.newBufferedReader(conntrackPath)) {
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null) {
                if (++linesRead > MAX_CONNTRACK_LINES) {
                    // Conntrack table too large; give up to avoid unbounded scan
                    return null;
                }
                InetSocketAddress result = parseConntrackLine(line, clientIp, clientPort, localIp, localPort);
                if (result != null) {
                    return result;
                }
            }
        } catch (IOException e) {
            // Cannot read conntrack -- return null for graceful fallback
            return null;
        }

        return null;
    }

    /**
     * Parses a single conntrack line and returns the original destination if
     * the line matches the given connection parameters.
     * <p>
     * Package-private for unit testing.
     */
    static InetSocketAddress parseConntrackLine(String line, String clientIp, int clientPort,
                                                String localIp, int localPort) {
        if (line == null || !line.contains("tcp") || !line.contains("ESTABLISHED")) {
            return null;
        }

        // A conntrack line has two "tuples" separated by whitespace.
        // Each tuple has src=, dst=, sport=, dport= fields.
        // The first tuple is the original direction; the second is the reply.
        //
        // Strategy: find all src=, dst=, sport=, dport= tokens in order.
        // Tokens 0-3 are the original tuple; tokens 4-7 are the reply tuple.

        String[] tokens = line.split("\\s+");

        String origSrc = null, origDst = null;
        int origSport = -1, origDport = -1;
        String replySrc = null;
        int replySport = -1;

        int srcCount = 0, dstCount = 0, sportCount = 0, dportCount = 0;

        for (String token : tokens) {
            if (token.startsWith("src=")) {
                String val = token.substring(4);
                srcCount++;
                if (srcCount == 1) {
                    origSrc = normalizeIp(val);
                } else if (srcCount == 2) {
                    replySrc = normalizeIp(val);
                }
            } else if (token.startsWith("dst=")) {
                String val = token.substring(4);
                dstCount++;
                if (dstCount == 1) {
                    origDst = normalizeIp(val);
                }
            } else if (token.startsWith("sport=")) {
                try {
                    int val = Integer.parseInt(token.substring(6));
                    sportCount++;
                    if (sportCount == 1) {
                        origSport = val;
                    } else if (sportCount == 2) {
                        replySport = val;
                    }
                } catch (NumberFormatException e) {
                    // skip
                }
            } else if (token.startsWith("dport=")) {
                try {
                    int val = Integer.parseInt(token.substring(6));
                    dportCount++;
                    if (dportCount == 1) {
                        origDport = val;
                    }
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }

        // Match: original src + sport = client, reply src + sport = our local address
        if (origSrc != null && origDst != null && origSport >= 0 && origDport >= 0
            && replySrc != null && replySport >= 0) {
            if (origSrc.equals(clientIp) && origSport == clientPort
                && replySrc.equals(localIp) && replySport == localPort) {
                try {
                    return new InetSocketAddress(origDst, origDport);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Normalizes an IP address string for comparison. Strips leading zeros
     * from IPv4 octets and lowercases IPv6 addresses.
     */
    static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        // Handle IPv6
        if (ip.contains(":")) {
            return ip.toLowerCase(Locale.ROOT);
        }
        // Handle IPv4 -- strip leading zeros from each octet
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return ip;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append('.');
            }
            try {
                sb.append(Integer.parseInt(parts[i]));
            } catch (NumberFormatException e) {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }
}
