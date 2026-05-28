package org.mockserver.socket.tls;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

public class KeyStoreFactoryTest {

    private static final String CA_CERT_PATH = "org/mockserver/authentication/mtls/ca.pem";
    private static final String LEAF_CERT_PATH = "org/mockserver/authentication/mtls/leaf-cert.pem";
    private static final String LEAF_KEY_PKCS8_PATH = "org/mockserver/authentication/mtls/leaf-key-pkcs8.pem";

    private KeyStoreFactory keyStoreFactory;

    @After
    public void cleanup() {
        if (keyStoreFactory != null) {
            File keyStoreFile = new File(keyStoreFactory.keyStoreFileName);
            if (keyStoreFile.exists()) {
                keyStoreFile.delete();
            }
        }
    }

    // --- Constants ---

    @Test
    public void shouldExposeKeyStoreTypeConstant() {
        assertThat(KeyStoreFactory.KEY_STORE_TYPE, is("jks"));
    }

    @Test
    public void shouldExposeKeyStorePasswordConstant() {
        assertThat(KeyStoreFactory.KEY_STORE_PASSWORD, is("changeit"));
    }

    @Test
    public void shouldExposeKeyStoreCertAliasConstant() {
        assertThat(KeyStoreFactory.KEY_STORE_CERT_ALIAS, is("mockserver-client-cert"));
    }

    @Test
    public void shouldExposeKeyStoreCAAliasConstant() {
        assertThat(KeyStoreFactory.KEY_STORE_CA_ALIAS, is("mockserver-ca-cert"));
    }

    // --- keyStoreFileName uniqueness ---

    @Test
    public void shouldGenerateUniqueKeyStoreFileNames() {
        Configuration config = configuration();
        MockServerLogger logger = new MockServerLogger();
        KeyStoreFactory factory1 = new KeyStoreFactory(config, logger);
        KeyStoreFactory factory2 = new KeyStoreFactory(config, logger);

        assertThat(factory1.keyStoreFileName, is(not(equalTo(factory2.keyStoreFileName))));
        assertThat(factory1.keyStoreFileName, startsWith("mockserver_keystore_"));
        assertThat(factory1.keyStoreFileName, endsWith("_jks"));
    }

    // --- loadOrCreateKeyStore with explicit key material ---

    @Test
    public void shouldCreateKeyStoreWithProvidedKeyMaterial() {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat(keyStore, is(notNullValue()));
    }

    @Test
    public void shouldContainCertAlias() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat(keyStore.containsAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS), is(true));
    }

    @Test
    public void shouldContainCAAliasAsTrustedCert() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat(keyStore.containsAlias(KeyStoreFactory.KEY_STORE_CA_ALIAS), is(true));
        assertThat(keyStore.isCertificateEntry(KeyStoreFactory.KEY_STORE_CA_ALIAS), is(true));
    }

    @Test
    public void shouldStorePrivateKeyUnderCertAlias() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat(keyStore.isKeyEntry(KeyStoreFactory.KEY_STORE_CERT_ALIAS), is(true));
    }

    // --- round-trip: write to disk and read back ---

    @Test
    public void shouldPersistKeyStoreToDiskAndReadBack() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        // verify file was written
        File keyStoreFile = new File(keyStoreFactory.keyStoreFileName);
        assertThat("keystore file should exist on disk", keyStoreFile.exists(), is(true));

        // read it back
        KeyStore reloaded = KeyStore.getInstance(KeyStoreFactory.KEY_STORE_TYPE);
        try (FileInputStream fis = new FileInputStream(keyStoreFile)) {
            reloaded.load(fis, KeyStoreFactory.KEY_STORE_PASSWORD.toCharArray());
        }

        assertThat(reloaded.containsAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS), is(true));
        assertThat(reloaded.containsAlias(KeyStoreFactory.KEY_STORE_CA_ALIAS), is(true));
    }

    @Test
    public void shouldReloadExistingKeyStoreFromDisk() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        // first call creates and persists
        keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        // second call on same factory should find the file and reload it
        KeyStore reloaded = keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat(reloaded.containsAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS), is(true));
        assertThat(reloaded.containsAlias(KeyStoreFactory.KEY_STORE_CA_ALIAS), is(true));
    }

    // --- sslContext ---

    @Test
    public void shouldCreateSSLContextWithProvidedKeyMaterial() {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        SSLContext sslContext = keyStoreFactory.sslContext(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat(sslContext, is(notNullValue()));
        assertThat(sslContext.getProtocol(), is("TLSv1.2"));
    }

    @Test
    public void shouldReturnSameSSLContextOnSubsequentCalls() {
        Configuration config = configuration();
        keyStoreFactory = new KeyStoreFactory(config, new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        SSLContext first = keyStoreFactory.sslContext(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );
        SSLContext second = keyStoreFactory.sslContext(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat("SSLContext should be cached", first, is(sameInstance(second)));
    }

    @Test
    public void shouldRebuildSSLContextWhenConfigurationRequiresIt() {
        Configuration config = configuration();
        keyStoreFactory = new KeyStoreFactory(config, new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        SSLContext first = keyStoreFactory.sslContext(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        // force rebuild
        config.rebuildTLSContext(true);

        SSLContext second = keyStoreFactory.sslContext(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        assertThat("SSLContext should be rebuilt", first, is(not(sameInstance(second))));
    }

    // --- loadOrCreateKeyStore (no-arg) auto-generates certs ---

    @Test
    public void shouldAutoGenerateCertsViaNoArgLoadOrCreateKeyStore() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore();

        assertThat(keyStore, is(notNullValue()));
        assertThat(keyStore.containsAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS), is(true));
        assertThat(keyStore.containsAlias(KeyStoreFactory.KEY_STORE_CA_ALIAS), is(true));
    }

    // --- sslContext() auto-generates certs ---

    @Test
    public void shouldAutoGenerateCertsViaSslContext() {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        SSLContext sslContext = keyStoreFactory.sslContext();

        assertThat(sslContext, is(notNullValue()));
        assertThat(sslContext.getProtocol(), is("TLSv1.2"));
    }

    // --- deprecated constructor ---

    @Test
    public void shouldWorkWithDeprecatedConstructor() throws Exception {
        keyStoreFactory = new KeyStoreFactory(new MockServerLogger());

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore();

        assertThat(keyStore, is(notNullValue()));
        assertThat(keyStore.containsAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS), is(true));
    }

    // --- certificate chain stored under cert alias ---

    @Test
    public void shouldStoreCertificateChainUnderCertAlias() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        KeyStore keyStore = keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        java.security.cert.Certificate[] chain = keyStore.getCertificateChain(KeyStoreFactory.KEY_STORE_CERT_ALIAS);
        assertThat(chain, is(notNullValue()));
        // chain should contain leaf + CA
        assertThat(chain.length, is(2));
    }

    // --- javax.net.ssl.trustStore system property ---

    @Test
    public void shouldSetTrustStoreSystemProperty() {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());

        PrivateKey privateKey = PEMToFile.privateKeyFromPEMFile(LEAF_KEY_PKCS8_PATH);
        X509Certificate leafCert = PEMToFile.x509FromPEMFile(LEAF_CERT_PATH);
        X509Certificate caCert = PEMToFile.x509FromPEMFile(CA_CERT_PATH);

        keyStoreFactory.loadOrCreateKeyStore(
            privateKey, leafCert, caCert, new X509Certificate[]{caCert}
        );

        String trustStore = System.getProperty("javax.net.ssl.trustStore");
        assertThat(trustStore, is(notNullValue()));
        assertThat(trustStore, containsString("mockserver_keystore_"));
    }
}
