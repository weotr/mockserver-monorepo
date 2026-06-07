package org.mockserver.netty.proxy;

import io.netty.channel.Channel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Resolves the original destination of a transparently intercepted TCP connection
 * by calling {@code getsockopt(fd, SOL_IP, SO_ORIGINAL_DST, ...)} via JNA on Linux.
 * <p>
 * This is an O(1) socket-option read — significantly faster than the O(n)
 * {@code /proc/net/nf_conntrack} scan used by {@link ConntrackOriginalDestinationResolver}.
 * It is tried first in the {@link CompositeOriginalDestinationResolver} chain.
 * <p>
 * <b>Platform requirements:</b>
 * <ul>
 *   <li>Linux (the {@code SO_ORIGINAL_DST} socket option is Linux-specific)</li>
 *   <li>Netty epoll transport ({@code EpollSocketChannel}) — the NIO transport does not
 *       expose the raw file descriptor, so this resolver returns {@code null} for NIO
 *       channels (allowing the conntrack resolver to take over)</li>
 *   <li>JNA native library loadable at runtime</li>
 * </ul>
 * <p>
 * On unsupported platforms (macOS, Windows, or Linux without epoll transport), this
 * resolver returns {@code null} from {@link #resolve(Channel)}, causing the composite
 * chain to proceed to the next strategy. It never throws.
 *
 * @see CompositeOriginalDestinationResolver
 * @see ConntrackOriginalDestinationResolver
 */
public class SoOriginalDstResolver implements TransparentProxyHandler.OriginalDestinationResolver {

    // Linux socket option constants
    static final int SOL_IP = 0;
    static final int SO_ORIGINAL_DST = 80;
    static final int SOL_IPV6 = 41;
    static final int IP6T_SO_ORIGINAL_DST = 80;

    // Address family constants (Linux)
    static final short AF_INET = 2;
    static final short AF_INET6 = 10;

    // sockaddr sizes
    static final int SOCKADDR_IN_SIZE = 16;
    static final int SOCKADDR_IN6_SIZE = 28;

    /**
     * Cached result of the platform-support check. Computed once at class-load time
     * to avoid repeated reflection/linkage attempts.
     */
    private static final boolean SUPPORTED = probeSupported();

    /**
     * Returns {@code true} if this resolver can function on the current platform:
     * Linux OS, Netty epoll transport available, and JNA native loadable.
     * <p>
     * All checks are performed defensively — any linkage error, class-not-found,
     * or unsatisfied-link causes this to return {@code false} so the resolver
     * harmlessly falls through to the next strategy in the chain.
     */
    public boolean isSupported() {
        return SUPPORTED;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean probeSupported() {
        // 1. Must be Linux
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return false;
        }

        // 2. Netty epoll transport must be available (classes loaded + native .so)
        try {
            if (!io.netty.channel.epoll.Epoll.isAvailable()) {
                return false;
            }
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            return false;
        }

        // 3. JNA must be loadable
        try {
            // Trigger class loading of the JNA binding to detect missing native
            Class.forName("com.sun.jna.Native");
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError | UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Resolves the original destination for the given channel.
     * <p>
     * Returns {@code null} (never throws) when:
     * <ul>
     *   <li>The platform is unsupported (not Linux, no epoll, no JNA)</li>
     *   <li>The channel is not an {@code EpollSocketChannel} (e.g., NIO transport)</li>
     *   <li>The {@code getsockopt} call fails (errno, decode failure, etc.)</li>
     * </ul>
     *
     * @param channel the accepted Netty channel
     * @return the original destination, or {@code null} if unavailable
     */
    @Override
    public InetSocketAddress resolve(Channel channel) {
        if (!SUPPORTED) {
            return null;
        }

        // EpollSocketChannel check — NIO channels do not expose an fd
        if (!isEpollSocketChannel(channel)) {
            return null;
        }

        try {
            return doResolve(channel);
        } catch (Exception e) {
            // Never let an exception escape — return null so the chain continues
            return null;
        }
    }

    /**
     * Checks whether the channel is an EpollSocketChannel. Isolated into a method
     * so the instanceof check does not trigger class loading on platforms where
     * the epoll classes are absent (the SUPPORTED flag guards this path).
     */
    private static boolean isEpollSocketChannel(Channel channel) {
        try {
            return channel instanceof io.netty.channel.epoll.EpollSocketChannel;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Performs the actual getsockopt call. Only called when SUPPORTED is true
     * and the channel is an EpollSocketChannel.
     */
    private static InetSocketAddress doResolve(Channel channel) {
        io.netty.channel.epoll.EpollSocketChannel epollChannel =
            (io.netty.channel.epoll.EpollSocketChannel) channel;

        int fd = epollChannel.fd().intValue();

        // Determine IPv4 vs IPv6 from the channel's local address
        InetSocketAddress localAddr = (InetSocketAddress) channel.localAddress();
        boolean ipv6 = localAddr != null && localAddr.getAddress() instanceof java.net.Inet6Address;

        int level = ipv6 ? SOL_IPV6 : SOL_IP;
        int optname = ipv6 ? IP6T_SO_ORIGINAL_DST : SO_ORIGINAL_DST;
        int bufSize = ipv6 ? SOCKADDR_IN6_SIZE : SOCKADDR_IN_SIZE;

        if (CLibrary.INSTANCE == null) {
            // probeSupported() confirmed the JNA class is present, but Native.load("c")
            // could still have failed (returns null) — fall through to the next resolver.
            return null;
        }

        com.sun.jna.Memory buf = new com.sun.jna.Memory(bufSize);
        com.sun.jna.ptr.IntByReference lenRef = new com.sun.jna.ptr.IntByReference(bufSize);

        try {
            CLibrary.INSTANCE.getsockopt(fd, level, optname, buf, lenRef);
        } catch (com.sun.jna.LastErrorException e) {
            // getsockopt failed — likely no REDIRECT rule or not a redirected socket
            return null;
        }

        byte[] sockaddr = buf.getByteArray(0, lenRef.getValue());
        return decodeSockaddr(sockaddr);
    }

    /**
     * Decodes a raw {@code sockaddr_in} or {@code sockaddr_in6} byte array into an
     * {@link InetSocketAddress}.
     * <p>
     * <b>Byte-order conventions in sockaddr structures:</b>
     * <ul>
     *   <li>{@code sa_family} (bytes 0-1): <b>host byte order</b> (native endianness).
     *       This field is of type {@code sa_family_t} which is a native-width type
     *       stored in the CPU's native byte order.</li>
     *   <li>{@code sin_port} / {@code sin6_port} (bytes 2-3): <b>network byte order</b>
     *       (big-endian), as is standard for all protocol-level fields.</li>
     *   <li>{@code sin_addr} (bytes 4-7) / {@code sin6_addr} (bytes 8-23): <b>network
     *       byte order</b> (big-endian).</li>
     *   <li>{@code sin6_scope_id} (bytes 24-27): host byte order (not used for address
     *       construction but available in the buffer).</li>
     * </ul>
     *
     * @param sockaddr the raw sockaddr bytes (at least 16 bytes for IPv4, 28 for IPv6)
     * @return the decoded address, or {@code null} for unknown family or short buffer
     */
    static InetSocketAddress decodeSockaddr(byte[] sockaddr) {
        if (sockaddr == null || sockaddr.length < 4) {
            return null;
        }

        // Read sa_family in native byte order
        ByteBuffer familyBuf = ByteBuffer.wrap(sockaddr, 0, 2).order(ByteOrder.nativeOrder());
        short family = familyBuf.getShort();

        if (family == AF_INET) {
            return decodeIpv4(sockaddr);
        } else if (family == AF_INET6) {
            return decodeIpv6(sockaddr);
        }

        return null;
    }

    private static InetSocketAddress decodeIpv4(byte[] sockaddr) {
        if (sockaddr.length < SOCKADDR_IN_SIZE) {
            return null;
        }

        // Port at bytes 2-3, big-endian (network order)
        int port = ((sockaddr[2] & 0xFF) << 8) | (sockaddr[3] & 0xFF);

        // IPv4 address at bytes 4-7, network order
        byte[] addr = new byte[4];
        System.arraycopy(sockaddr, 4, addr, 0, 4);

        try {
            return new InetSocketAddress(InetAddress.getByAddress(addr), port);
        } catch (UnknownHostException e) {
            // Should never happen for a 4-byte address
            return null;
        }
    }

    private static InetSocketAddress decodeIpv6(byte[] sockaddr) {
        if (sockaddr.length < SOCKADDR_IN6_SIZE) {
            return null;
        }

        // Port at bytes 2-3, big-endian (network order)
        int port = ((sockaddr[2] & 0xFF) << 8) | (sockaddr[3] & 0xFF);

        // IPv6 address at bytes 8-23 (16 bytes), network order
        // (bytes 4-7 are sin6_flowinfo, skipped)
        byte[] addr = new byte[16];
        System.arraycopy(sockaddr, 8, addr, 0, 16);

        try {
            return new InetSocketAddress(InetAddress.getByAddress(addr), port);
        } catch (UnknownHostException e) {
            // Should never happen for a 16-byte address
            return null;
        }
    }

    /**
     * Minimal JNA binding to the C library's {@code getsockopt} function.
     * <p>
     * Uses JNA's interface-based binding with {@code LastErrorException} to
     * capture errno on failure. The binding is loaded lazily via the
     * {@link com.sun.jna.Native#load} mechanism.
     */
    interface CLibrary extends com.sun.jna.Library {
        CLibrary INSTANCE = loadInstance();

        /**
         * Loads the C library instance, returning null if JNA is not available.
         * The null check is safe because this code path is only reached when
         * {@link #SUPPORTED} is true (which requires JNA to be loadable).
         */
        static CLibrary loadInstance() {
            try {
                return com.sun.jna.Native.load("c", CLibrary.class);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                return null;
            }
        }

        /**
         * {@code int getsockopt(int sockfd, int level, int optname, void *optval, socklen_t *optlen)}
         *
         * @throws com.sun.jna.LastErrorException if the syscall returns -1 (errno is captured)
         */
        @SuppressWarnings("UnusedReturnValue")
        int getsockopt(int sockfd, int level, int optname,
                       com.sun.jna.Pointer optval,
                       com.sun.jna.ptr.IntByReference optlen) throws com.sun.jna.LastErrorException;
    }
}
