package org.mockserver.socket.tls;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.file.FileReader;

import java.io.File;
import java.io.FileWriter;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;

public class PEMToFileTest {

    private static final String CA_CERT_PATH = "org/mockserver/authentication/mtls/ca.pem";
    private static final String LEAF_CERT_PATH = "org/mockserver/authentication/mtls/leaf-cert.pem";
    private static final String LEAF_KEY_PKCS8_PATH = "org/mockserver/authentication/mtls/leaf-key-pkcs8.pem";
    private static final String LEAF_KEY_PATH = "org/mockserver/authentication/mtls/leaf-key.pem";
    private static final String LEAF_CERT_CHAIN_PATH = "org/mockserver/authentication/mtls/leaf-cert-chain.pem";
    private static final String CA_KEY_PKCS8_PATH = "org/mockserver/authentication/mtls/ca-key-pkcs8.pem";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // --- privateKeyToPEM ---

    // The switch in privateKeyToPEM dispatches on JWT algorithm identifiers
    // (RS256/RS384/RS512, ES256/ES384/ES512). Standard JCA PrivateKey instances
    // return "RSA"/"EC" from getAlgorithm(), so the named cases are structurally
    // unreachable from any key obtainable through KeyFactory and only the
    // default IllegalArgumentException branch is observable here.
    @Test
    public void shouldThrowIllegalArgumentExceptionForJCAAlgorithmName() {
        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        assertThat("standard JCA algorithm name, not a JWT identifier", privateKey.getAlgorithm(), is("RSA"));
        try {
            PEMToFile.privateKeyToPEM(privateKey);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("Error invalid algorithm has been provided"));
        }
    }

    // --- certToPEM(X509Certificate...) ---

    @Test
    public void shouldConvertSingleCertToPEM() throws Exception {
        X509Certificate cert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        String pem = PEMToFile.certToPEM(cert);

        assertThat(pem, containsString("-----BEGIN CERTIFICATE-----"));
        assertThat(pem, containsString("-----END CERTIFICATE-----"));
    }

    @Test
    public void shouldConvertMultipleCertsToPEM() throws Exception {
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);

        String pem = PEMToFile.certToPEM(leafCert, caCert);

        // should contain two certificate blocks
        int firstBegin = pem.indexOf("-----BEGIN CERTIFICATE-----");
        int secondBegin = pem.indexOf("-----BEGIN CERTIFICATE-----", firstBegin + 1);
        assertThat("should contain two BEGIN CERTIFICATE markers", secondBegin, is(greaterThan(firstBegin)));
    }

    // --- certToPEM(byte[]...) ---

    @Test
    public void shouldConvertCertBytesToPEM() throws Exception {
        X509Certificate cert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);
        byte[] encoded = cert.getEncoded();

        String pem = PEMToFile.certToPEM(encoded);

        assertThat(pem, containsString("-----BEGIN CERTIFICATE-----"));
        assertThat(pem, containsString("-----END CERTIFICATE-----"));
    }

    @Test
    public void shouldConvertMultipleCertBytesToPEM() throws Exception {
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);

        String pem = PEMToFile.certToPEM(leafCert.getEncoded(), caCert.getEncoded());

        int firstEnd = pem.indexOf("-----END CERTIFICATE-----");
        int secondEnd = pem.indexOf("-----END CERTIFICATE-----", firstEnd + 1);
        assertThat("should contain two END CERTIFICATE markers", secondEnd, is(greaterThan(firstEnd)));
    }

    // --- privateKeyBytesFromPEM ---

    @Test
    public void shouldExtractPrivateKeyBytesFromPKCS8PEM() {
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_KEY_PKCS8_PATH);

        byte[] keyBytes = PEMToFile.privateKeyBytesFromPEM(pem);

        assertThat(keyBytes, is(notNullValue()));
        assertThat(keyBytes.length, is(greaterThan(0)));
    }

    @Test
    public void shouldStripBeginEndHeadersWhenExtractingBytes() {
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_KEY_PKCS8_PATH);

        byte[] keyBytes = PEMToFile.privateKeyBytesFromPEM(pem);

        // Verify the bytes are valid base64-decoded content (not still containing ASCII headers)
        String base64Encoded = Base64.getMimeEncoder().encodeToString(keyBytes);
        assertThat(base64Encoded, not(containsString("BEGIN")));
        assertThat(base64Encoded, not(containsString("END")));
    }

    // --- keySpecFromPEM ---

    @Test
    public void shouldReturnPKCS8EncodedKeySpec() {
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_KEY_PKCS8_PATH);

        PKCS8EncodedKeySpec keySpec = (PKCS8EncodedKeySpec) PEMToFile.keySpecFromPEM(pem);

        assertThat(keySpec, is(notNullValue()));
        assertThat(keySpec.getEncoded().length, is(greaterThan(0)));
        assertThat(keySpec.getFormat(), is("PKCS#8"));
    }

    // --- privateKeyFromPEMFile ---

    @Test
    public void shouldLoadPrivateKeyFromPKCS8PEMFile() {
        PrivateKey key = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);

        assertThat(key, is(notNullValue()));
        assertThat(key.getAlgorithm(), is("RSA"));
    }

    @Test
    public void shouldLoadPrivateKeyFromTraditionalRSAPEMFile() {
        PrivateKey key = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PATH);

        assertThat(key, is(notNullValue()));
        assertThat(key.getAlgorithm(), is("RSA"));
    }

    @Test
    public void shouldThrowRuntimeExceptionForMissingPrivateKeyFile() {
        try {
            PEMToFile.privateKeyFromPEMFile("nonexistent/path/to/key.pem");
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), is("Exception reading private key from PEM file"));
        }
    }

    // --- privateKeyFromPEM ---

    @Test
    public void shouldLoadPrivateKeyFromPEMString() {
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_KEY_PKCS8_PATH);

        PrivateKey key = PEMToFile.privateKeyFromPEM(pem);

        assertThat(key, is(notNullValue()));
        assertThat(key.getAlgorithm(), is("RSA"));
    }

    @Test
    public void shouldLoadPrivateKeyFromTraditionalRSAPEMString() {
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_KEY_PATH);

        PrivateKey key = PEMToFile.privateKeyFromPEM(pem);

        assertThat(key, is(notNullValue()));
        assertThat(key.getAlgorithm(), is("RSA"));
    }

    @Test
    public void shouldThrowRuntimeExceptionForMalformedPrivateKeyPEM() {
        try {
            PEMToFile.privateKeyFromPEM("this is not a valid PEM string");
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Exception reading private key from PEM file"));
        }
    }

    // --- x509FromPEMFile ---

    @Test
    public void shouldLoadX509CertFromPEMFile() {
        X509Certificate cert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        assertThat(cert, is(notNullValue()));
        assertThat(cert.getSubjectDN().getName(), containsString("MockServer"));
    }

    @Test
    public void shouldThrowRuntimeExceptionForMissingX509File() {
        String missingFile = "nonexistent/path/to/cert.pem";
        try {
            PEMToFile.x509FromPEMFile(missingFile);
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), is("Exception reading X509 from PEM file " + missingFile));
        }
    }

    // --- x509FromPEM ---

    @Test
    public void shouldLoadX509CertFromPEMString() {
        String pem = FileReader.readFileFromClassPathOrPath(CA_CERT_PATH);

        X509Certificate cert = PEMToFile.x509FromPEM(pem);

        assertThat(cert, is(notNullValue()));
        assertThat(cert.getSubjectDN().getName(), containsString("MockServer"));
    }

    @Test
    public void shouldThrowRuntimeExceptionForMalformedX509PEM() {
        try {
            PEMToFile.x509FromPEM("this is not a valid certificate");
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Exception reading X509 from PEM"));
        }
    }

    // --- x509ChainFromPEMFile ---

    @Test
    public void shouldLoadCertChainFromPEMFile() {
        List<X509Certificate> chain = PEMToFile.x509ChainFromPEMFile(LEAF_CERT_CHAIN_PATH);

        assertThat(chain, hasSize(2));
        // first cert is the leaf, second is the CA
        assertThat(chain.get(0).getSubjectDN().getName(), containsString("MockServer"));
        assertThat(chain.get(1).getSubjectDN().getName(), containsString("MockServer"));
    }

    @Test
    public void shouldReturnSingleCertChainForSingleCertFile() {
        List<X509Certificate> chain = PEMToFile.x509ChainFromPEMFile(CA_CERT_PATH);

        assertThat(chain, hasSize(1));
    }

    // --- x509ChainFromPEM ---

    @Test
    public void shouldLoadCertChainFromPEMString() {
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_CERT_CHAIN_PATH);

        List<X509Certificate> chain = PEMToFile.x509ChainFromPEM(pem);

        assertThat(chain, hasSize(2));
    }

    // --- validX509PEMFileExists ---

    @Test
    public void shouldReturnTrueForValidPEMFile() throws Exception {
        File certFile = tempFolder.newFile("valid-cert.pem");
        String certPem = FileReader.readFileFromClassPathOrPath(CA_CERT_PATH);
        try (FileWriter writer = new FileWriter(certFile)) {
            writer.write(certPem);
        }

        boolean result = PEMToFile.validX509PEMFileExists(certFile.getAbsolutePath());

        assertThat(result, is(true));
    }

    @Test
    public void shouldReturnFalseForMissingFile() {
        boolean result = PEMToFile.validX509PEMFileExists("nonexistent/path/cert.pem");

        assertThat(result, is(false));
    }

    @Test
    public void shouldReturnFalseForInvalidPEMFile() throws Exception {
        File invalidFile = tempFolder.newFile("invalid-cert.pem");
        try (FileWriter writer = new FileWriter(invalidFile)) {
            writer.write("this is not a valid PEM certificate");
        }

        boolean result = PEMToFile.validX509PEMFileExists(invalidFile.getAbsolutePath());

        assertThat(result, is(false));
    }

    // --- round-trip: certToPEM -> x509FromPEM ---

    @Test
    public void shouldRoundTripCertThroughPEM() throws Exception {
        X509Certificate originalCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        String pem = PEMToFile.certToPEM(originalCert);
        X509Certificate roundTrippedCert = PEMToFile.x509FromPEM(pem);

        assertThat(roundTrippedCert, is(equalTo(originalCert)));
    }

    // --- round-trip: certToPEM(byte[]) -> x509FromPEM ---

    @Test
    public void shouldRoundTripCertBytesThroughPEM() throws Exception {
        X509Certificate originalCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);

        String pem = PEMToFile.certToPEM(originalCert.getEncoded());
        X509Certificate roundTrippedCert = PEMToFile.x509FromPEM(pem);

        assertThat(roundTrippedCert, is(equalTo(originalCert)));
    }

    // --- privateKeyFromPEMFile with classpath resource ---

    @Test
    public void shouldLoadCAPrivateKeyFromPKCS8PEMFile() {
        PrivateKey key = PEMToFile.privateKeyFromPEMFile(CA_KEY_PKCS8_PATH);

        assertThat(key, is(notNullValue()));
        assertThat(key.getAlgorithm(), is("RSA"));
    }

    // --- privateKeyBytesFromPEM with different key types ---

    @Test
    public void shouldExtractBytesFromRSAPrivateKeyPEM() {
        // Build a PEM string with RSA PRIVATE KEY headers
        String pem = FileReader.readFileFromClassPathOrPath(LEAF_KEY_PATH);

        byte[] keyBytes = PEMToFile.privateKeyBytesFromPEM(pem);

        assertThat(keyBytes, is(notNullValue()));
        assertThat(keyBytes.length, is(greaterThan(100)));
    }
}
