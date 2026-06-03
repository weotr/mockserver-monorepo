package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SoOriginalDstResolver}, focusing on the pure {@code decodeSockaddr}
 * logic which can be validated on any platform (including macOS), plus the safe
 * fall-through behaviour for unsupported platforms and non-epoll channels.
 * <p>
 * The actual {@code getsockopt(SO_ORIGINAL_DST)} syscall path can only be tested
 * on Linux with epoll transport, iptables REDIRECT rules, and root — that is
 * deferred to CI/e2e integration tests.
 */
public class SoOriginalDstResolverTest {

    private final SoOriginalDstResolver resolver = new SoOriginalDstResolver();

    // --- decodeSockaddr: IPv4 ---

    @Test
    public void shouldDecodeIpv4Sockaddr() {
        // sockaddr_in for 93.184.216.34:80
        // sa_family = AF_INET (2) in native byte order
        // sin_port = 80 = 0x0050 in network byte order (big-endian)
        // sin_addr = 93.184.216.34 = 0x5D B8 D8 22 in network byte order
        byte[] sockaddr = new byte[16];

        // Family in native byte order
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 80 = 0x0050 in big-endian
        sockaddr[2] = 0x00;
        sockaddr[3] = 0x50;

        // IPv4 address: 93.184.216.34
        sockaddr[4] = 93;
        sockaddr[5] = (byte) 184;
        sockaddr[6] = (byte) 216;
        sockaddr[7] = 34;

        // when
        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        // then
        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("93.184.216.34"));
        assertThat(result.getPort(), is(80));
    }

    @Test
    public void shouldDecodeIpv4SockaddrWithHighPort() {
        // sockaddr_in for 10.0.0.1:8443
        byte[] sockaddr = new byte[16];

        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 8443 = 0x20FB in big-endian
        sockaddr[2] = 0x20;
        sockaddr[3] = (byte) 0xFB;

        // IPv4 address: 10.0.0.1
        sockaddr[4] = 10;
        sockaddr[5] = 0;
        sockaddr[6] = 0;
        sockaddr[7] = 1;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(result.getPort(), is(8443));
    }

    @Test
    public void shouldDecodeIpv4SockaddrPort443() {
        // sockaddr_in for 192.168.1.100:443
        byte[] sockaddr = new byte[16];

        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 443 = 0x01BB in big-endian
        sockaddr[2] = 0x01;
        sockaddr[3] = (byte) 0xBB;

        // IPv4 address: 192.168.1.100
        sockaddr[4] = (byte) 192;
        sockaddr[5] = (byte) 168;
        sockaddr[6] = 1;
        sockaddr[7] = 100;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("192.168.1.100"));
        assertThat(result.getPort(), is(443));
    }

    @Test
    public void shouldDecodeIpv4SockaddrMaxPort() {
        // sockaddr_in for 127.0.0.1:65535
        byte[] sockaddr = new byte[16];

        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 65535 = 0xFFFF in big-endian
        sockaddr[2] = (byte) 0xFF;
        sockaddr[3] = (byte) 0xFF;

        // IPv4 address: 127.0.0.1
        sockaddr[4] = 127;
        sockaddr[5] = 0;
        sockaddr[6] = 0;
        sockaddr[7] = 1;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("127.0.0.1"));
        assertThat(result.getPort(), is(65535));
    }

    // --- decodeSockaddr: IPv6 ---

    @Test
    public void shouldDecodeIpv6Sockaddr() {
        // sockaddr_in6 for [2001:db8::1]:8080
        byte[] sockaddr = new byte[28];

        // Family AF_INET6 in native byte order
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET6);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 8080 = 0x1F90 in big-endian
        sockaddr[2] = 0x1F;
        sockaddr[3] = (byte) 0x90;

        // bytes 4-7: sin6_flowinfo (zero)

        // bytes 8-23: sin6_addr = 2001:0db8:0000:0000:0000:0000:0000:0001
        sockaddr[8] = 0x20;
        sockaddr[9] = 0x01;
        sockaddr[10] = 0x0d;
        sockaddr[11] = (byte) 0xb8;
        // bytes 12-21 are all zero (already)
        sockaddr[23] = 0x01;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("2001:db8:0:0:0:0:0:1"));
        assertThat(result.getPort(), is(8080));
    }

    @Test
    public void shouldDecodeIpv6LoopbackSockaddr() {
        // sockaddr_in6 for [::1]:443
        byte[] sockaddr = new byte[28];

        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET6);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 443 = 0x01BB in big-endian
        sockaddr[2] = 0x01;
        sockaddr[3] = (byte) 0xBB;

        // sin6_addr = ::1 (all zeros except last byte)
        sockaddr[23] = 0x01;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("0:0:0:0:0:0:0:1"));
        assertThat(result.getPort(), is(443));
    }

    @Test
    public void shouldDecodeIpv6FullAddress() {
        // sockaddr_in6 for [fe80::1ff:fe23:4567:890a]:1234
        byte[] sockaddr = new byte[28];

        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET6);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 1234 = 0x04D2 in big-endian
        sockaddr[2] = 0x04;
        sockaddr[3] = (byte) 0xD2;

        // sin6_addr = fe80:0000:0000:0000:01ff:fe23:4567:890a
        sockaddr[8] = (byte) 0xfe;
        sockaddr[9] = (byte) 0x80;
        // 10-15 zero
        sockaddr[16] = 0x01;
        sockaddr[17] = (byte) 0xff;
        sockaddr[18] = (byte) 0xfe;
        sockaddr[19] = 0x23;
        sockaddr[20] = 0x45;
        sockaddr[21] = 0x67;
        sockaddr[22] = (byte) 0x89;
        sockaddr[23] = 0x0a;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("fe80:0:0:0:1ff:fe23:4567:890a"));
        assertThat(result.getPort(), is(1234));
    }

    // --- decodeSockaddr: error / edge cases ---

    @Test
    public void shouldReturnNullForNullSockaddr() {
        assertThat(SoOriginalDstResolver.decodeSockaddr(null), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForEmptySockaddr() {
        assertThat(SoOriginalDstResolver.decodeSockaddr(new byte[0]), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForTooShortBuffer() {
        // Less than 4 bytes — cannot even read the family
        assertThat(SoOriginalDstResolver.decodeSockaddr(new byte[3]), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForUnknownAddressFamily() {
        byte[] sockaddr = new byte[16];
        // Set family to 99 (unknown) in native byte order
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort((short) 99);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        assertThat(SoOriginalDstResolver.decodeSockaddr(sockaddr), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForTruncatedIpv4() {
        // AF_INET family but buffer too short for full sockaddr_in
        byte[] sockaddr = new byte[8]; // need 16
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        assertThat(SoOriginalDstResolver.decodeSockaddr(sockaddr), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForTruncatedIpv6() {
        // AF_INET6 family but buffer too short for full sockaddr_in6
        byte[] sockaddr = new byte[16]; // need 28
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET6);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        assertThat(SoOriginalDstResolver.decodeSockaddr(sockaddr), is(nullValue()));
    }

    @Test
    public void shouldDecodeIpv4WithExtraPadding() {
        // Larger buffer than needed — should still decode correctly from the first 16 bytes
        byte[] sockaddr = new byte[32];

        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        sockaddr[2] = 0x00;
        sockaddr[3] = 0x50; // port 80
        sockaddr[4] = 8;
        sockaddr[5] = 8;
        sockaddr[6] = 8;
        sockaddr[7] = 8;    // 8.8.8.8

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("8.8.8.8"));
        assertThat(result.getPort(), is(80));
    }

    // --- Platform safety: isSupported + resolve on macOS ---

    @Test
    public void isSupportedReturnsFalseOnNonLinux() {
        // This test runs on macOS in CI — isSupported() must be false
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!osName.contains("linux")) {
            assertThat("isSupported() should be false on " + osName,
                resolver.isSupported(), is(false));
        }
        // On Linux without epoll, it may also be false — that is acceptable
    }

    @Test
    public void resolveReturnsNullOnNonEpollChannel() {
        // A mock channel is not an EpollSocketChannel — should return null
        Channel mockChannel = mock(Channel.class);
        InetSocketAddress result = resolver.resolve(mockChannel);
        assertThat(result, is(nullValue()));
    }

    @Test
    public void resolveReturnsNullForNullChannel() {
        // null channel should not throw
        InetSocketAddress result = resolver.resolve(null);
        assertThat(result, is(nullValue()));
    }

    @Test
    public void resolveDoesNotThrowOnUnsupportedPlatform() {
        // On macOS, resolve should never throw — it should return null
        Channel mockChannel = mock(Channel.class);
        try {
            InetSocketAddress result = resolver.resolve(mockChannel);
            assertThat(result, is(nullValue()));
        } catch (Exception e) {
            // This should not happen
            assertThat("resolve() should not throw, but threw: " + e, false, is(true));
        }
    }

    // --- Port zero edge case ---

    @Test
    public void shouldDecodePortZero() {
        byte[] sockaddr = new byte[16];
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        // Port 0
        sockaddr[2] = 0x00;
        sockaddr[3] = 0x00;

        sockaddr[4] = 1;
        sockaddr[5] = 2;
        sockaddr[6] = 3;
        sockaddr[7] = 4;

        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("1.2.3.4"));
        assertThat(result.getPort(), is(0));
    }

    // --- All-zeros address ---

    @Test
    public void shouldDecodeAllZerosIpv4Address() {
        byte[] sockaddr = new byte[16];
        ByteBuffer familyBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        familyBuf.putShort(SoOriginalDstResolver.AF_INET);
        System.arraycopy(familyBuf.array(), 0, sockaddr, 0, 2);

        sockaddr[2] = 0x00;
        sockaddr[3] = 0x50; // port 80

        // All zeros address (0.0.0.0)
        InetSocketAddress result = SoOriginalDstResolver.decodeSockaddr(sockaddr);

        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("0.0.0.0"));
        assertThat(result.getPort(), is(80));
    }
}
