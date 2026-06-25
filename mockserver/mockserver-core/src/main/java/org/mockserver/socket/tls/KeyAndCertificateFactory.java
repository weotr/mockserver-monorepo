package org.mockserver.socket.tls;

import org.mockserver.keys.AsymmetricKeyPairAlgorithm;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

/**
 * @author jamesdbloom
 */
public interface KeyAndCertificateFactory {

    /**
     * default key pair generation and signing algorithm
     */
    AsymmetricKeyPairAlgorithm DEFAULT_KEY_GENERATION_AND_SIGNING_ALGORITHM = AsymmetricKeyPairAlgorithm.RSA2048_SHA256;
    /**
     * Number of years the generated CA and leaf certificates remain valid. The
     * generated CA is the trust anchor users pin into their trust stores, so it
     * needs to outlive a typical test/CI lifetime rather than expiring after a
     * single year and silently breaking pinned-CA deployments. Ten years is long
     * enough to avoid surprise expiry while staying well below the X.509 ceiling
     * that older clients (e.g. Apple iOS 8, issue #6) reject.
     */
    long CERTIFICATE_VALIDITY_YEARS = 10;
    /**
     * The not-before validity bound for a freshly issued certificate: the current time minus 5 days,
     * just in case the software clock goes back due to time synchronization.
     * <p>
     * Computed per issuance (rather than once at class load) so that certificates generated on the fly
     * — e.g. leaf certificates minted long after the JVM started — are anchored to issuance time rather
     * than to JVM start time.
     */
    static Date notBefore() {
        return new Date(System.currentTimeMillis() - 86400000L * 5);
    }

    /**
     * The not-after validity bound for a freshly issued certificate, {@link #CERTIFICATE_VALIDITY_YEARS}
     * years in the future from issuance time.
     * <p>
     * The maximum possible value in the X.509 specification is 9999-12-31 23:59:59
     * (new Date(253402300799000L)), but Apple iOS 8 fails with a certificate
     * expiration date greater than Mon, 24 Jan 6084 02:07:59 GMT (issue #6).
     * <p>
     * Computed per issuance (rather than once at class load) so that on-the-fly generated certificates
     * are anchored to issuance time rather than to JVM start time.
     */
    static Date notAfter() {
        return new Date(System.currentTimeMillis() + 86400000L * 365 * CERTIFICATE_VALIDITY_YEARS);
    }
    /**
     * CN for CA distinguishing name
     */
    String ROOT_COMMON_NAME = "www.mockserver.com";
    /**
     * default CN for leaf distinguishing name
     */
    String CERTIFICATE_DOMAIN = "localhost";
    /**
     * O for distinguishing name
     */
    String ORGANISATION = "MockServer";
    /**
     * L for distinguishing name
     */
    String LOCALITY = "London";
    /**
     * ST for distinguishing name
     */
    String STATE = "England";
    /**
     * C for distinguishing name
     */
    String COUNTRY = "UK";

    @SuppressWarnings("unused")
    void buildAndSaveCertificateAuthorityPrivateKeyAndX509Certificate();

    void buildAndSavePrivateKeyAndX509Certificate();

    boolean certificateNotYetCreated();

    PrivateKey privateKey();

    X509Certificate x509Certificate();

    X509Certificate certificateAuthorityX509Certificate();

    List<X509Certificate> certificateChain();

}
