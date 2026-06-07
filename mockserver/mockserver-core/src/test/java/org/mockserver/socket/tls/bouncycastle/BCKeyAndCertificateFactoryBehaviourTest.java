package org.mockserver.socket.tls.bouncycastle;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;

public class BCKeyAndCertificateFactoryBehaviourTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Configuration configuration;
    private BCKeyAndCertificateFactory factory;

    @Before
    public void setUp() {
        configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(tempFolder.getRoot().getAbsolutePath());
        factory = new BCKeyAndCertificateFactory(configuration, new MockServerLogger());
    }

    // --- Key generation ---

    @Test
    public void shouldGenerateRSAPrivateKey() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        PrivateKey privateKey = factory.privateKey();
        assertThat(privateKey, notNullValue());
        assertThat(privateKey, instanceOf(RSAPrivateKey.class));
        assertThat(((RSAPrivateKey) privateKey).getModulus().bitLength(), greaterThanOrEqualTo(2048));
    }

    @Test
    public void shouldGenerateX509Certificate() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        assertThat(cert, notNullValue());
        assertThat(cert.getType(), equalTo("X.509"));
        assertThat(cert.getVersion(), equalTo(3));
    }

    @Test
    public void shouldGenerateCACertificate() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate caCert = factory.certificateAuthorityX509Certificate();
        assertThat(caCert, notNullValue());
        assertThat(caCert.getType(), equalTo("X.509"));
        // CA cert should have basicConstraints with CA=true
        assertThat(caCert.getBasicConstraints(), greaterThanOrEqualTo(0));
    }

    // --- SAN handling ---

    @Test
    public void shouldIncludeDefaultLocalhostInSAN() {
        // given - default configuration includes localhost as domain name
        configuration.sslCertificateDomainName("localhost");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> dnsNames = extractDnsNames(sans);
        assertThat(dnsNames, hasItem("localhost"));
    }

    @Test
    public void shouldIncludeCustomDomainNamesInSAN() {
        // given
        configuration.sslCertificateDomainName("myapp.example.com");
        configuration.sslSubjectAlternativeNameDomains("api.example.com", "www.example.com");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> dnsNames = extractDnsNames(sans);
        assertThat(dnsNames, hasItem("myapp.example.com"));
        assertThat(dnsNames, hasItem("api.example.com"));
        assertThat(dnsNames, hasItem("www.example.com"));
    }

    @Test
    public void shouldIncludeIPAddressesInSAN() {
        // given
        configuration.sslCertificateDomainName("localhost");
        configuration.sslSubjectAlternativeNameIps("127.0.0.1", "192.168.1.1");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> ipAddresses = extractIPAddresses(sans);
        assertThat(ipAddresses, hasItem("127.0.0.1"));
        assertThat(ipAddresses, hasItem("192.168.1.1"));
    }

    @Test
    public void shouldIncludeBothDomainsAndIPsInSAN() {
        // given
        configuration.sslCertificateDomainName("myhost.local");
        configuration.sslSubjectAlternativeNameDomains("extra.local");
        configuration.sslSubjectAlternativeNameIps("10.0.0.1");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> dnsNames = extractDnsNames(sans);
        Set<String> ipAddresses = extractIPAddresses(sans);

        assertThat(dnsNames, hasItem("myhost.local"));
        assertThat(dnsNames, hasItem("extra.local"));
        assertThat(ipAddresses, hasItem("10.0.0.1"));
    }

    // --- Certificate chain ---

    @Test
    public void shouldReturnCertificateChainWithLeafAndCA() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        List<X509Certificate> chain = factory.certificateChain();
        assertThat(chain, notNullValue());
        assertThat(chain.size(), equalTo(2));
        // first is the leaf cert
        assertThat(chain.get(0), equalTo(factory.x509Certificate()));
        // second is the CA cert
        assertThat(chain.get(1), equalTo(factory.certificateAuthorityX509Certificate()));
    }

    // --- Leaf cert signed by CA ---

    @Test
    public void shouldSignLeafCertificateWithCA() throws Exception {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate leaf = factory.x509Certificate();
        X509Certificate ca = factory.certificateAuthorityX509Certificate();
        // verify leaf cert is signed by CA - this will throw if verification fails
        leaf.verify(ca.getPublicKey());
    }

    // --- Certificate not yet created check ---

    @Test
    public void shouldReportCertificateNotYetCreatedBeforeGeneration() {
        // when / then - before generating, certificate is not yet created
        assertTrue(factory.certificateNotYetCreated());
    }

    @Test
    public void shouldReportCertificateCreatedAfterGeneration() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        assertFalse(factory.certificateNotYetCreated());
    }

    // --- Subject DN ---

    @Test
    public void shouldSetCorrectSubjectDNForLeafCert() {
        // given
        configuration.sslCertificateDomainName("test.mock-server.com");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        String subjectDN = cert.getSubjectX500Principal().getName();
        assertThat(subjectDN, org.hamcrest.Matchers.containsString("CN=test.mock-server.com"));
        assertThat(subjectDN, org.hamcrest.Matchers.containsString("O=MockServer"));
    }

    @Test
    public void shouldSetCorrectIssuerDNForCACert() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate caCert = factory.certificateAuthorityX509Certificate();
        String issuerDN = caCert.getIssuerX500Principal().getName();
        assertThat(issuerDN, org.hamcrest.Matchers.containsString("CN=www.mockserver.com"));
        assertThat(issuerDN, org.hamcrest.Matchers.containsString("O=MockServer"));
    }

    // --- CA cert is self-signed ---

    @Test
    public void shouldGenerateSelfSignedCACert() throws Exception {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate caCert = factory.certificateAuthorityX509Certificate();
        // self-signed means issuer = subject
        assertThat(caCert.getIssuerX500Principal(), equalTo(caCert.getSubjectX500Principal()));
        // and it can verify itself
        caCert.verify(caCert.getPublicKey());
    }

    // --- Helper methods ---

    private Collection<List<?>> getSANs(X509Certificate cert) {
        try {
            return cert.getSubjectAlternativeNames();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get SANs from certificate", e);
        }
    }

    /**
     * DNS names have GeneralName type 2
     */
    private Set<String> extractDnsNames(Collection<List<?>> sans) {
        return sans.stream()
            .filter(san -> Integer.valueOf(2).equals(san.get(0)))
            .map(san -> (String) san.get(1))
            .collect(Collectors.toSet());
    }

    /**
     * IP addresses have GeneralName type 7
     */
    private Set<String> extractIPAddresses(Collection<List<?>> sans) {
        return sans.stream()
            .filter(san -> Integer.valueOf(7).equals(san.get(0)))
            .map(san -> (String) san.get(1))
            .collect(Collectors.toSet());
    }
}
