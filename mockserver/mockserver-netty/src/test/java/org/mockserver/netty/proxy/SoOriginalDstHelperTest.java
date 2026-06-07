package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SoOriginalDstHelperTest {

    private Path tempDir;
    private Path nfConntrackFile;
    private Path ipConntrackFile;
    private SoOriginalDstHelper.ConntrackPathProvider originalProvider;

    @Before
    public void setUp() throws IOException {
        originalProvider = SoOriginalDstHelper.pathProvider;
        tempDir = Files.createTempDirectory("conntrack-test");
        nfConntrackFile = tempDir.resolve("nf_conntrack");
        ipConntrackFile = tempDir.resolve("ip_conntrack");

        SoOriginalDstHelper.pathProvider = new SoOriginalDstHelper.ConntrackPathProvider() {
            @Override
            public Path nfConntrackPath() {
                return nfConntrackFile;
            }

            @Override
            public Path ipConntrackPath() {
                return ipConntrackFile;
            }
        };
    }

    @After
    public void tearDown() throws IOException {
        SoOriginalDstHelper.pathProvider = originalProvider;
        Files.deleteIfExists(nfConntrackFile);
        Files.deleteIfExists(ipConntrackFile);
        Files.deleteIfExists(tempDir);
    }

    // --- isSupported() tests ---

    @Test
    public void isSupportedReflectsOS() {
        boolean isLinux = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("linux");
        assertThat(SoOriginalDstHelper.isSupported(), is(isLinux));
    }

    // --- getOriginalDestination() platform guard ---

    @Test
    public void getOriginalDestinationThrowsOnNonLinux() {
        assumeTrue("only runs on non-Linux", !SoOriginalDstHelper.isSupported());

        Channel mockChannel = mock(Channel.class);
        try {
            SoOriginalDstHelper.getOriginalDestination(mockChannel);
            assertThat("should have thrown", false, is(true));
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage(), containsString("SO_ORIGINAL_DST requires Linux"));
            assertThat(e.getMessage(), containsString(System.getProperty("os.name", "unknown")));
        }
    }

    @Test
    public void getOriginalDestinationReturnsNullForNullChannel() {
        assumeTrue("only runs on Linux", SoOriginalDstHelper.isSupported());
        assertThat(SoOriginalDstHelper.getOriginalDestination(null), is(nullValue()));
    }

    // --- normalizeIp() tests ---

    @Test
    public void normalizeIpStripsLeadingZeros() {
        assertThat(SoOriginalDstHelper.normalizeIp("010.000.000.001"), is("10.0.0.1"));
        assertThat(SoOriginalDstHelper.normalizeIp("192.168.001.010"), is("192.168.1.10"));
    }

    @Test
    public void normalizeIpHandlesNormalIpv4() {
        assertThat(SoOriginalDstHelper.normalizeIp("10.0.0.2"), is("10.0.0.2"));
        assertThat(SoOriginalDstHelper.normalizeIp("127.0.0.1"), is("127.0.0.1"));
    }

    @Test
    public void normalizeIpLowercasesIpv6() {
        assertThat(SoOriginalDstHelper.normalizeIp("FE80::1"), is("fe80::1"));
        assertThat(SoOriginalDstHelper.normalizeIp("::1"), is("::1"));
    }

    @Test
    public void normalizeIpHandlesNull() {
        assertThat(SoOriginalDstHelper.normalizeIp(null), is(nullValue()));
    }

    // --- parseConntrackLine() tests ---

    @Test
    public void parseConntrackLineMatchesRedirectedConnection() {
        // Typical nf_conntrack line for an iptables REDIRECT:
        // Original: client(10.0.0.2:45678) -> external(93.184.216.34:80)
        // After REDIRECT: client(10.0.0.2:45678) -> mockserver(127.0.0.1:1080)
        // Reply tuple: mockserver(127.0.0.1:1080) -> client(10.0.0.2:45678)
        String line = "ipv4     2 tcp      6 431999 ESTABLISHED " +
            "src=10.0.0.2 dst=93.184.216.34 sport=45678 dport=80 " +
            "src=127.0.0.1 dst=10.0.0.2 sport=1080 dport=45678 [ASSURED] mark=0 use=2";

        InetSocketAddress result = SoOriginalDstHelper.parseConntrackLine(
            line, "10.0.0.2", 45678, "127.0.0.1", 1080
        );

        assertThat(result, is(notNullValue()));
        assertThat(result.getHostString(), is("93.184.216.34"));
        assertThat(result.getPort(), is(80));
    }

    @Test
    public void parseConntrackLineReturnsNullForNonMatchingLine() {
        String line = "ipv4     2 tcp      6 431999 ESTABLISHED " +
            "src=10.0.0.5 dst=8.8.8.8 sport=12345 dport=53 " +
            "src=8.8.8.8 dst=10.0.0.5 sport=53 dport=12345 [ASSURED] mark=0 use=2";

        InetSocketAddress result = SoOriginalDstHelper.parseConntrackLine(
            line, "10.0.0.2", 45678, "127.0.0.1", 1080
        );

        assertThat(result, is(nullValue()));
    }

    @Test
    public void parseConntrackLineReturnsNullForNonTcpLine() {
        String line = "ipv4     2 udp      17 30 src=10.0.0.2 dst=8.8.8.8 sport=45678 dport=53 " +
            "src=8.8.8.8 dst=10.0.0.2 sport=53 dport=45678 [ASSURED] mark=0 use=2";

        InetSocketAddress result = SoOriginalDstHelper.parseConntrackLine(
            line, "10.0.0.2", 45678, "127.0.0.1", 1080
        );

        assertThat(result, is(nullValue()));
    }

    @Test
    public void parseConntrackLineReturnsNullForNullLine() {
        assertThat(SoOriginalDstHelper.parseConntrackLine(null, "10.0.0.2", 45678, "127.0.0.1", 1080),
            is(nullValue()));
    }

    @Test
    public void parseConntrackLineHandlesHttpsPort() {
        String line = "ipv4     2 tcp      6 431999 ESTABLISHED " +
            "src=10.0.0.2 dst=93.184.216.34 sport=45678 dport=443 " +
            "src=127.0.0.1 dst=10.0.0.2 sport=1080 dport=45678 [ASSURED] mark=0 use=2";

        InetSocketAddress result = SoOriginalDstHelper.parseConntrackLine(
            line, "10.0.0.2", 45678, "127.0.0.1", 1080
        );

        assertThat(result, is(notNullValue()));
        assertThat(result.getHostString(), is("93.184.216.34"));
        assertThat(result.getPort(), is(443));
    }

    // --- lookupConntrack() tests with mock files ---

    @Test
    public void lookupConntrackFindsMatchInNfConntrack() throws IOException {
        writeFile(nfConntrackFile,
            "ipv4     2 tcp      6 300 ESTABLISHED src=10.0.0.5 dst=8.8.8.8 sport=11111 dport=53 src=8.8.8.8 dst=10.0.0.5 sport=53 dport=11111 [ASSURED] mark=0 use=2\n" +
                "ipv4     2 tcp      6 431999 ESTABLISHED src=10.0.0.2 dst=93.184.216.34 sport=45678 dport=80 src=127.0.0.1 dst=10.0.0.2 sport=1080 dport=45678 [ASSURED] mark=0 use=2\n"
        );

        InetSocketAddress result = SoOriginalDstHelper.lookupConntrack(
            new InetSocketAddress("10.0.0.2", 45678),
            new InetSocketAddress("127.0.0.1", 1080)
        );

        assertThat(result, is(notNullValue()));
        assertThat(result.getHostString(), is("93.184.216.34"));
        assertThat(result.getPort(), is(80));
    }

    @Test
    public void lookupConntrackFallsBackToIpConntrack() throws IOException {
        // nf_conntrack does not exist; ip_conntrack does
        writeFile(ipConntrackFile,
            "tcp      6 431999 ESTABLISHED src=10.0.0.2 dst=93.184.216.34 sport=45678 dport=80 src=127.0.0.1 dst=10.0.0.2 sport=1080 dport=45678 [ASSURED] mark=0 use=2\n"
        );

        InetSocketAddress result = SoOriginalDstHelper.lookupConntrack(
            new InetSocketAddress("10.0.0.2", 45678),
            new InetSocketAddress("127.0.0.1", 1080)
        );

        assertThat(result, is(notNullValue()));
        assertThat(result.getHostString(), is("93.184.216.34"));
        assertThat(result.getPort(), is(80));
    }

    @Test
    public void lookupConntrackReturnsNullWhenNoFileExists() {
        // Neither file exists
        InetSocketAddress result = SoOriginalDstHelper.lookupConntrack(
            new InetSocketAddress("10.0.0.2", 45678),
            new InetSocketAddress("127.0.0.1", 1080)
        );

        assertThat(result, is(nullValue()));
    }

    @Test
    public void lookupConntrackReturnsNullWhenNoMatchFound() throws IOException {
        writeFile(nfConntrackFile,
            "ipv4     2 tcp      6 300 ESTABLISHED src=10.0.0.5 dst=8.8.8.8 sport=11111 dport=53 src=8.8.8.8 dst=10.0.0.5 sport=53 dport=11111 [ASSURED] mark=0 use=2\n"
        );

        InetSocketAddress result = SoOriginalDstHelper.lookupConntrack(
            new InetSocketAddress("10.0.0.2", 45678),
            new InetSocketAddress("127.0.0.1", 1080)
        );

        assertThat(result, is(nullValue()));
    }

    private void writeFile(Path path, String content) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(content);
        }
    }
}
