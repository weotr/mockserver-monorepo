package org.mockserver.mock.dns;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class DnsIntentRegistryTest {

    @Before
    public void setUp() {
        DnsIntentRegistry.getInstance().clear();
    }

    @After
    public void tearDown() {
        DnsIntentRegistry.getInstance().clear();
    }

    @Test
    public void shouldRecordAndRecoverIPv4() throws UnknownHostException {
        // given
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});

        // when
        DnsIntentRegistry.getInstance().record(ip, "api.example.com");

        // then
        assertThat(DnsIntentRegistry.getInstance().recover(ip), is("api.example.com"));
    }

    @Test
    public void shouldRecordAndRecoverIPv6() throws UnknownHostException {
        // given
        InetAddress ip = InetAddress.getByAddress(new byte[]{
            0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        });

        // when
        DnsIntentRegistry.getInstance().record(ip, "ipv6.example.com");

        // then
        assertThat(DnsIntentRegistry.getInstance().recover(ip), is("ipv6.example.com"));
    }

    @Test
    public void shouldStripTrailingDot() throws UnknownHostException {
        // given
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});

        // when — DNS qnames often have a trailing dot
        DnsIntentRegistry.getInstance().record(ip, "host.example.com.");

        // then — trailing dot should be stripped
        assertThat(DnsIntentRegistry.getInstance().recover(ip), is("host.example.com"));
    }

    @Test
    public void shouldReturnNullWhenNoEntry() throws UnknownHostException {
        // given — no recording
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 99});

        // then
        assertThat(DnsIntentRegistry.getInstance().recover(ip), is(nullValue()));
    }

    @Test
    public void shouldIgnoreNullIp() {
        // when — should not throw
        DnsIntentRegistry.getInstance().record(null, "host.example.com");

        // then — size unchanged
        assertThat(DnsIntentRegistry.getInstance().size(), is(0));
    }

    @Test
    public void shouldIgnoreNullHostname() throws UnknownHostException {
        // given
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 3});

        // when — should not throw
        DnsIntentRegistry.getInstance().record(ip, null);

        // then — size unchanged
        assertThat(DnsIntentRegistry.getInstance().size(), is(0));
    }

    @Test
    public void shouldReturnNullForNullRecoverArg() {
        // when/then — should not throw, just return null
        assertThat(DnsIntentRegistry.getInstance().recover(null), is(nullValue()));
    }

    @Test
    public void shouldClearAllEntries() throws UnknownHostException {
        // given
        DnsIntentRegistry.getInstance().record(
            InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), "a.example.com"
        );
        DnsIntentRegistry.getInstance().record(
            InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), "b.example.com"
        );
        assertThat(DnsIntentRegistry.getInstance().size(), is(2));

        // when
        DnsIntentRegistry.getInstance().clear();

        // then
        assertThat(DnsIntentRegistry.getInstance().size(), is(0));
        assertThat(DnsIntentRegistry.getInstance().recover(
            InetAddress.getByAddress(new byte[]{10, 0, 0, 1})
        ), is(nullValue()));
    }

    @Test
    public void shouldEvictOldestBeyondCap() throws UnknownHostException {
        // given — a small-cap registry
        DnsIntentRegistry registry = new DnsIntentRegistry(5);
        for (int i = 0; i < 8; i++) {
            registry.record(
                InetAddress.getByAddress(new byte[]{10, 0, 0, (byte) i}),
                "host" + i + ".example.com"
            );
        }

        // then — size bounded
        assertThat(registry.size(), is(5));

        // oldest entries (0, 1, 2) should be evicted
        assertThat(registry.recover(InetAddress.getByAddress(new byte[]{10, 0, 0, 0})), is(nullValue()));
        assertThat(registry.recover(InetAddress.getByAddress(new byte[]{10, 0, 0, 1})), is(nullValue()));
        assertThat(registry.recover(InetAddress.getByAddress(new byte[]{10, 0, 0, 2})), is(nullValue()));

        // newest entries (3, 4, 5, 6, 7) should still be present
        assertThat(registry.recover(InetAddress.getByAddress(new byte[]{10, 0, 0, 3})), is("host3.example.com"));
        assertThat(registry.recover(InetAddress.getByAddress(new byte[]{10, 0, 0, 7})), is("host7.example.com"));
    }

    @Test
    public void shouldUpdateHostnameForSameIp() throws UnknownHostException {
        // given
        InetAddress ip = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        DnsIntentRegistry.getInstance().record(ip, "old.example.com");

        // when — same IP, different hostname (most-recent-answer-wins)
        DnsIntentRegistry.getInstance().record(ip, "new.example.com");

        // then
        assertThat(DnsIntentRegistry.getInstance().recover(ip), is("new.example.com"));
        assertThat(DnsIntentRegistry.getInstance().size(), is(1));
    }
}
