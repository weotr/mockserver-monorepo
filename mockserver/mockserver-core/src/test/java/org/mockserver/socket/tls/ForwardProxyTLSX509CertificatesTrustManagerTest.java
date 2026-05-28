package org.mockserver.socket.tls;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Enum-contract tests for {@link ForwardProxyTLSX509CertificatesTrustManager}.
 */
public class ForwardProxyTLSX509CertificatesTrustManagerTest {

    @Test
    public void shouldHaveExactlyThreeValues() {
        // then
        assertThat(ForwardProxyTLSX509CertificatesTrustManager.values(), arrayWithSize(3));
    }

    @Test
    public void shouldResolveAllNamedConstants() {
        // then
        assertThat(ForwardProxyTLSX509CertificatesTrustManager.valueOf("ANY"), is(ForwardProxyTLSX509CertificatesTrustManager.ANY));
        assertThat(ForwardProxyTLSX509CertificatesTrustManager.valueOf("JVM"), is(ForwardProxyTLSX509CertificatesTrustManager.JVM));
        assertThat(ForwardProxyTLSX509CertificatesTrustManager.valueOf("CUSTOM"), is(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForUnknownValue() {
        ForwardProxyTLSX509CertificatesTrustManager.valueOf("OTHER");
    }
}
