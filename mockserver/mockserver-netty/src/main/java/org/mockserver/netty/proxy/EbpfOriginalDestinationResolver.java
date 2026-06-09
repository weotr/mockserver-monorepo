package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.mockserver.configuration.Configuration;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Resolves the original destination of a transparently intercepted TCP connection
 * by reading a pinned BPF hash map populated by an external eBPF program.
 * <p>
 * <b>Architecture:</b> an external BPF program (typically attached to a cgroup via
 * {@code cgroup/connect4} or {@code cgroup/sock_ops}) records the original destination
 * address in a BPF hash map keyed by socket cookie. This resolver reads that map
 * entry for the accepted socket, extracts the original destination, and optionally
 * deletes the entry to prevent stale data.
 * <p>
 * <b>How it works:</b>
 * <ol>
 *   <li>Open the pinned BPF map via {@code bpf(BPF_OBJ_GET, path)} to get a map fd</li>
 *   <li>Get the socket cookie via {@code getsockopt(fd, SOL_SOCKET, SO_COOKIE)}</li>
 *   <li>Lookup the original destination: {@code bpf(BPF_MAP_LOOKUP_ELEM, map_fd, &cookie)}</li>
 *   <li>Delete the consumed entry: {@code bpf(BPF_MAP_DELETE_ELEM, map_fd, &cookie)}</li>
 *   <li>Decode the 6-byte value (4-byte IPv4 + 2-byte port, network byte order)</li>
 * </ol>
 * <p>
 * <b>BPF map format:</b>
 * <ul>
 *   <li>Map type: {@code BPF_MAP_TYPE_HASH}</li>
 *   <li>Key: {@code u64} socket cookie (8 bytes, native byte order)</li>
 *   <li>Value: 6 bytes — 4-byte IPv4 address + 2-byte port (both network byte order)</li>
 *   <li>Pinned at: configured path (default {@code /sys/fs/bpf/mockserver_orig_dst})</li>
 * </ul>
 * <p>
 * <b>Requirements:</b>
 * <ul>
 *   <li>Linux with BPF support ({@code CONFIG_BPF_SYSCALL=y})</li>
 *   <li>Netty epoll transport ({@code EpollSocketChannel}) for fd access</li>
 *   <li>JNA native library for {@code syscall()} and {@code getsockopt()}</li>
 *   <li>{@code CAP_BPF} capability (or root) for BPF map operations</li>
 *   <li>An external BPF program populating the pinned map</li>
 *   <li>Configuration: {@code mockserver.transparentProxyEbpf=true}</li>
 * </ul>
 * <p>
 * <b>Chain position:</b> in the {@link CompositeOriginalDestinationResolver} default
 * chain, eBPF is placed <b>after TPROXY</b> and <b>before SO_ORIGINAL_DST</b>. Like
 * SO_ORIGINAL_DST, the eBPF map lookup is O(1). It is tried before SO_ORIGINAL_DST
 * because when an eBPF program is deployed, its data is more authoritative than the
 * Netfilter conntrack state (the BPF program captures the destination before any
 * NAT rewrites). When eBPF is not enabled or the map is unavailable, this resolver
 * returns {@code null} and the chain falls through to SO_ORIGINAL_DST.
 * <p>
 * <b>No new dependencies:</b> this resolver uses JNA (already a MockServer dependency
 * for {@link SoOriginalDstResolver}) and the Linux {@code bpf()} / {@code getsockopt()}
 * syscalls. No BPF loader library is needed — the BPF program is loaded externally.
 *
 * @see CompositeOriginalDestinationResolver
 * @see SoOriginalDstResolver
 * @see TransparentProxyHandler
 */
public class EbpfOriginalDestinationResolver implements TransparentProxyHandler.OriginalDestinationResolver {

    // Linux syscall numbers for bpf()
    static final long SYS_BPF_X86_64 = 321;
    static final long SYS_BPF_AARCH64 = 280;

    // BPF commands
    static final int BPF_MAP_LOOKUP_ELEM = 1;
    static final int BPF_MAP_DELETE_ELEM = 3;
    static final int BPF_OBJ_GET = 7;

    // Socket option for cookie
    static final int SOL_SOCKET = 1;
    static final int SO_COOKIE = 57;

    // Map value layout: 4 bytes IPv4 addr + 2 bytes port (network byte order)
    static final int MAP_VALUE_SIZE = 6;
    static final int SOCKET_COOKIE_SIZE = 8;

    /**
     * Size of the bpf_attr union for BPF_OBJ_GET. We only need the pathname field
     * (a pointer), which starts at offset 0 in the union. The union must be at least
     * as large as the kernel expects. We use a 64-byte buffer (larger than any current
     * bpf_attr variant uses for BPF_OBJ_GET).
     */
    static final int BPF_ATTR_SIZE_OBJ_GET = 64;

    /**
     * Size of the bpf_attr union for BPF_MAP_LOOKUP_ELEM / BPF_MAP_DELETE_ELEM.
     * The relevant fields are: map_fd (4 bytes at offset 0), key (8 bytes at offset 8),
     * value (8 bytes at offset 16). We use 64 bytes for safety.
     */
    static final int BPF_ATTR_SIZE_MAP_OPS = 64;

    private final Configuration configuration;

    /**
     * Cached result of platform support check.
     */
    private static final boolean PLATFORM_SUPPORTED = probePlatformSupported();

    /**
     * Cached syscall number for bpf(), determined at class-load time.
     */
    private static final long SYS_BPF = detectSysBpf();

    /**
     * Cached map file descriptor. Opened lazily on first use, cached for the
     * lifetime of this resolver. -1 means not yet opened; -2 means open failed.
     * <p>
     * The fd is intentionally retained (not closed) for the resolver's lifetime:
     * there is one fd per resolver instance (one per server), it is reused across
     * all connections, and it is reclaimed by the OS at JVM exit. A {@code -2}
     * "failed" sentinel is permanent — if the external BPF program pins the map
     * after MockServer starts, restart MockServer (or set the path before start).
     */
    private volatile int mapFd = -1;

    public EbpfOriginalDestinationResolver(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Returns {@code true} if the platform supports eBPF map operations:
     * Linux OS, Netty epoll transport, and JNA.
     */
    public boolean isPlatformSupported() {
        return PLATFORM_SUPPORTED;
    }

    private static boolean probePlatformSupported() {
        // Must be Linux
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return false;
        }

        // Netty epoll must be available
        try {
            if (!io.netty.channel.epoll.Epoll.isAvailable()) {
                return false;
            }
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            return false;
        }

        // JNA must be loadable
        try {
            Class.forName("com.sun.jna.Native");
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError | UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static long detectSysBpf() {
        String arch = System.getProperty("os.arch", "");
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return SYS_BPF_AARCH64;
        }
        // Default to x86_64
        return SYS_BPF_X86_64;
    }

    @Override
    public InetSocketAddress resolve(Channel channel) {
        // Gate 1: config flag
        if (!Boolean.TRUE.equals(configuration.transparentProxyEbpf())) {
            return null;
        }

        // Gate 2: platform support
        if (!PLATFORM_SUPPORTED) {
            return null;
        }

        // Gate 3: must be EpollSocketChannel
        if (!isEpollSocketChannel(channel)) {
            return null;
        }

        try {
            return doResolve(channel);
        } catch (Exception e) {
            // Never let an exception escape
            return null;
        }
    }

    private static boolean isEpollSocketChannel(Channel channel) {
        try {
            return channel instanceof io.netty.channel.epoll.EpollSocketChannel;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Performs the actual eBPF map lookup. Only called when all gates pass.
     */
    private InetSocketAddress doResolve(Channel channel) {
        // 1. Ensure we have a map fd
        int fd = getOrOpenMapFd();
        if (fd < 0) {
            return null;
        }

        // 2. Get socket cookie
        io.netty.channel.epoll.EpollSocketChannel epollChannel =
            (io.netty.channel.epoll.EpollSocketChannel) channel;
        int sockFd = epollChannel.fd().intValue();
        long cookie = getSocketCookie(sockFd);
        if (cookie < 0) {
            return null;
        }

        // 3. Lookup in BPF map
        byte[] value = bpfMapLookup(fd, cookie);
        if (value == null || value.length < MAP_VALUE_SIZE) {
            return null;
        }

        // 4. Delete the consumed entry (best-effort, don't fail if it doesn't work)
        bpfMapDelete(fd, cookie);

        // 5. Decode: 4 bytes IPv4 addr + 2 bytes port (network byte order)
        return decodeMapValue(value);
    }

    /**
     * Opens the pinned BPF map and caches the fd. Thread-safe via double-checked locking.
     *
     * @return the map fd, or -1 if unavailable
     */
    private int getOrOpenMapFd() {
        int fd = mapFd;
        if (fd >= 0) {
            return fd;
        }
        if (fd == -2) {
            // Previously failed to open
            return -1;
        }
        synchronized (this) {
            fd = mapFd;
            if (fd >= 0) {
                return fd;
            }
            if (fd == -2) {
                return -1;
            }
            fd = bpfObjGet(configuration.transparentProxyEbpfMapPath());
            if (fd < 0) {
                mapFd = -2; // Mark as failed
                return -1;
            }
            mapFd = fd;
            return fd;
        }
    }

    // ------------------------------------------------------------------
    // JNA-based BPF syscall wrappers
    // ------------------------------------------------------------------

    /**
     * Opens a pinned BPF object (map) via {@code bpf(BPF_OBJ_GET, &attr)}.
     *
     * @param path the pinned map path (e.g., /sys/fs/bpf/mockserver_orig_dst)
     * @return the file descriptor, or -1 on failure
     */
    static int bpfObjGet(String path) {
        if (BpfSyscall.INSTANCE == null) {
            return -1;
        }

        try {
            // Build bpf_attr for BPF_OBJ_GET:
            // struct { __aligned_u64 pathname; ... }
            // pathname is at offset 0, stored as a 64-bit pointer
            byte[] pathBytes = (path + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.sun.jna.Memory pathMem = new com.sun.jna.Memory(pathBytes.length);
            pathMem.write(0, pathBytes, 0, pathBytes.length);

            com.sun.jna.Memory attr = new com.sun.jna.Memory(BPF_ATTR_SIZE_OBJ_GET);
            attr.clear();
            // Write pathname pointer at offset 0 as a 64-bit value
            attr.setLong(0, com.sun.jna.Pointer.nativeValue(pathMem));

            long result = BpfSyscall.INSTANCE.syscall(
                SYS_BPF, BPF_OBJ_GET, attr, BPF_ATTR_SIZE_OBJ_GET);
            if (result < 0) {
                return -1;
            }
            return (int) result;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Gets the socket cookie via {@code getsockopt(SOL_SOCKET, SO_COOKIE)}.
     *
     * @param sockFd the socket file descriptor
     * @return the socket cookie, or -1 on failure
     */
    static long getSocketCookie(int sockFd) {
        if (BpfSyscall.INSTANCE == null) {
            return -1;
        }

        try {
            com.sun.jna.Memory buf = new com.sun.jna.Memory(SOCKET_COOKIE_SIZE);
            com.sun.jna.ptr.IntByReference lenRef =
                new com.sun.jna.ptr.IntByReference(SOCKET_COOKIE_SIZE);

            BpfSyscall.INSTANCE.getsockopt(sockFd, SOL_SOCKET, SO_COOKIE, buf, lenRef);

            return buf.getLong(0);
        } catch (com.sun.jna.LastErrorException e) {
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Looks up an element in a BPF map via {@code bpf(BPF_MAP_LOOKUP_ELEM, &attr)}.
     *
     * @param mapFd  the map file descriptor
     * @param cookie the socket cookie (map key)
     * @return the value bytes, or null if not found
     */
    static byte[] bpfMapLookup(int mapFd, long cookie) {
        if (BpfSyscall.INSTANCE == null) {
            return null;
        }

        try {
            // Key memory: u64 socket cookie
            com.sun.jna.Memory keyMem = new com.sun.jna.Memory(SOCKET_COOKIE_SIZE);
            keyMem.setLong(0, cookie);

            // Value memory: 6 bytes (IPv4 addr + port)
            com.sun.jna.Memory valueMem = new com.sun.jna.Memory(MAP_VALUE_SIZE);
            valueMem.clear();

            // Build bpf_attr for BPF_MAP_LOOKUP_ELEM:
            // struct { __u32 map_fd; __u32 pad0; __aligned_u64 key; __aligned_u64 value; ... }
            com.sun.jna.Memory attr = new com.sun.jna.Memory(BPF_ATTR_SIZE_MAP_OPS);
            attr.clear();
            attr.setInt(0, mapFd);           // map_fd at offset 0
            // pad at offset 4
            attr.setLong(8, com.sun.jna.Pointer.nativeValue(keyMem));    // key ptr at offset 8
            attr.setLong(16, com.sun.jna.Pointer.nativeValue(valueMem)); // value ptr at offset 16

            long result = BpfSyscall.INSTANCE.syscall(
                SYS_BPF, BPF_MAP_LOOKUP_ELEM, attr, BPF_ATTR_SIZE_MAP_OPS);
            if (result != 0) {
                return null; // Element not found or error
            }

            return valueMem.getByteArray(0, MAP_VALUE_SIZE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Deletes an element from a BPF map via {@code bpf(BPF_MAP_DELETE_ELEM, &attr)}.
     * Best-effort: failures are silently ignored.
     *
     * @param mapFd  the map file descriptor
     * @param cookie the socket cookie (map key)
     */
    static void bpfMapDelete(int mapFd, long cookie) {
        if (BpfSyscall.INSTANCE == null) {
            return;
        }

        try {
            com.sun.jna.Memory keyMem = new com.sun.jna.Memory(SOCKET_COOKIE_SIZE);
            keyMem.setLong(0, cookie);

            com.sun.jna.Memory attr = new com.sun.jna.Memory(BPF_ATTR_SIZE_MAP_OPS);
            attr.clear();
            attr.setInt(0, mapFd);
            attr.setLong(8, com.sun.jna.Pointer.nativeValue(keyMem));

            BpfSyscall.INSTANCE.syscall(
                SYS_BPF, BPF_MAP_DELETE_ELEM, attr, BPF_ATTR_SIZE_MAP_OPS);
        } catch (Exception e) {
            // Best-effort delete, ignore failures
        }
    }

    /**
     * Decodes a 6-byte BPF map value into an {@link InetSocketAddress}.
     * <p>
     * Layout: [0-3] IPv4 address in network byte order, [4-5] port in network byte order.
     *
     * @param value the raw value bytes (must be at least 6 bytes)
     * @return the decoded address, or null on error
     */
    static InetSocketAddress decodeMapValue(byte[] value) {
        if (value == null || value.length < MAP_VALUE_SIZE) {
            return null;
        }

        // IPv4 address: bytes 0-3 (network byte order)
        byte[] addrBytes = new byte[4];
        System.arraycopy(value, 0, addrBytes, 0, 4);

        // Port: bytes 4-5 (network byte order = big-endian)
        int port = ((value[4] & 0xFF) << 8) | (value[5] & 0xFF);

        try {
            return new InetSocketAddress(InetAddress.getByAddress(addrBytes), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * JNA binding for the Linux {@code syscall()} and {@code getsockopt()} functions.
     * Used to invoke the {@code bpf()} system call and read socket cookies.
     * <p>
     * This reuses the same pattern as {@link SoOriginalDstResolver.CLibrary} but adds
     * the {@code syscall()} function for BPF operations.
     */
    interface BpfSyscall extends com.sun.jna.Library {
        BpfSyscall INSTANCE = loadInstance();

        static BpfSyscall loadInstance() {
            try {
                return com.sun.jna.Native.load("c", BpfSyscall.class);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                return null;
            }
        }

        /**
         * {@code long syscall(long number, ...)}
         * <p>
         * We define a fixed-arity overload for the bpf() case:
         * {@code syscall(__NR_bpf, int cmd, void *attr, unsigned int size)}
         */
        long syscall(long number, int cmd, com.sun.jna.Pointer attr, int size);

        /**
         * {@code int getsockopt(int sockfd, int level, int optname, void *optval, socklen_t *optlen)}
         */
        @SuppressWarnings("UnusedReturnValue")
        int getsockopt(int sockfd, int level, int optname,
                       com.sun.jna.Pointer optval,
                       com.sun.jna.ptr.IntByReference optlen) throws com.sun.jna.LastErrorException;
    }
}
