package org.mockserver.socket.tls.bouncycastle;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyAndCertificateFactory;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * @author jnormington
 */
public class BCKeyAndCertificateFactoryTest {

    private final KeyAndCertificateFactory keyAndCertificateFactory = new BCKeyAndCertificateFactory(configuration(), new MockServerLogger());

    @Test
    public void shouldCreateCACertWithPositiveSerialNumber() {
        keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();

        assertThat("The ca cert serial number is non-negative", keyAndCertificateFactory.certificateAuthorityX509Certificate().getSerialNumber().compareTo(BigInteger.ZERO) > 0, is(true));
    }

    @Test
    public void shouldCreateClientCertWithPositiveSerialNumber() {
        keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();

        assertThat("The client cert serial number is non-negative", keyAndCertificateFactory.x509Certificate().getSerialNumber().compareTo(BigInteger.ZERO) > 0, is(true));
    }

    @Test
    public void shouldCreateCertificatesValidForTheDocumentedLongLivedPeriod() {
        keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();

        long now = System.currentTimeMillis();
        // generated CA is the trust anchor users pin, so it must stay valid for the documented period
        assertCertificateValidity("ca cert", keyAndCertificateFactory.certificateAuthorityX509Certificate(), now);
        assertCertificateValidity("leaf cert", keyAndCertificateFactory.x509Certificate(), now);
    }

    private static void assertCertificateValidity(String description, X509Certificate certificate, long now) {
        // notBefore is back-dated, guarding against clock skew, but must not be in the future
        Date notBefore = certificate.getNotBefore();
        assertThat("The " + description + " notBefore is not in the future", notBefore.getTime(), is(lessThanOrEqualTo(now)));

        // notAfter must be at least (validity years - 1 day of slack) in the future
        long expectedMinimumNotAfter = now + TimeUnit.DAYS.toMillis(365L * KeyAndCertificateFactory.CERTIFICATE_VALIDITY_YEARS - 1);
        Date notAfter = certificate.getNotAfter();
        assertThat("The " + description + " notAfter is at least the documented validity period in the future", notAfter.getTime(), is(greaterThanOrEqualTo(expectedMinimumNotAfter)));
    }

}
