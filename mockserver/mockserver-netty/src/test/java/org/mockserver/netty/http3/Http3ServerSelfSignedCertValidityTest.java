package org.mockserver.netty.http3;

import org.junit.Test;
import org.mockserver.socket.tls.KeyAndCertificateFactory;

import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Verifies the HTTP/3 self-signed certificate shares the documented long-lived
 * validity period ({@link KeyAndCertificateFactory#CERTIFICATE_VALIDITY_YEARS})
 * rather than expiring after a single year. This guards against the HTTP/3 cert
 * silently drifting back to a short validity and breaking pinned-CA HTTP/3
 * deployments.
 * <p>
 * The certificate is generated with pure BouncyCastle/JCA, so this test does not
 * need the native QUIC transport and is not gated on it.
 */
public class Http3ServerSelfSignedCertValidityTest {

    @Test
    public void shouldGenerateSelfSignedCertWithDocumentedLongLivedValidity() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(256, new SecureRandom());
        KeyPair keyPair = keyPairGen.generateKeyPair();

        Method generateSelfSignedCert = Http3Server.class.getDeclaredMethod("generateSelfSignedCert", KeyPair.class);
        generateSelfSignedCert.setAccessible(true);

        long now = System.currentTimeMillis();
        X509Certificate certificate = (X509Certificate) generateSelfSignedCert.invoke(null, keyPair);

        Date notBefore = certificate.getNotBefore();
        assertThat("notBefore is not in the future", notBefore.getTime(), is(lessThanOrEqualTo(now + TimeUnit.MINUTES.toMillis(1))));

        long expectedMinimumNotAfter = now + TimeUnit.DAYS.toMillis(365L * KeyAndCertificateFactory.CERTIFICATE_VALIDITY_YEARS - 1);
        Date notAfter = certificate.getNotAfter();
        assertThat("notAfter is at least the documented validity period in the future", notAfter.getTime(), is(greaterThanOrEqualTo(expectedMinimumNotAfter)));
    }
}
