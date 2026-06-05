package org.mockserver.configuration;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.ConfigurationProperties.REDACTED_VALUE;
import static org.mockserver.configuration.ConfigurationProperties.isSensitivePropertyName;

/**
 * Verifies that sensitive property names are detected and their values
 * are redacted in property-file log dumps.
 */
public class ConfigurationPropertiesRedactionTest {

    // --- isSensitivePropertyName: positive cases ---

    @Test
    public void shouldDetectPasswordProperty() {
        assertThat(isSensitivePropertyName("mockserver.forwardProxyAuthenticationPassword"), is(true));
        assertThat(isSensitivePropertyName("mockserver.proxyAuthenticationPassword"), is(true));
    }

    @Test
    public void shouldDetectSecretProperty() {
        assertThat(isSensitivePropertyName("mockserver.blobStoreSecretAccessKey"), is(true));
    }

    @Test
    public void shouldDetectAccessKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.blobStoreAccessKeyId"), is(true));
    }

    @Test
    public void shouldDetectApiKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.llmApiKey"), is(true));
    }

    @Test
    public void shouldDetectConnectionStringProperty() {
        assertThat(isSensitivePropertyName("mockserver.blobStoreConnectionString"), is(true));
    }

    @Test
    public void shouldDetectPrivateKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.forwardProxyPrivateKey"), is(true));
        assertThat(isSensitivePropertyName("mockserver.certificateAuthorityPrivateKey"), is(true));
        assertThat(isSensitivePropertyName("mockserver.privateKeyPath"), is(true));
        assertThat(isSensitivePropertyName("mockserver.controlPlanePrivateKeyPath"), is(true));
    }

    @Test
    public void shouldDetectTokenProperty() {
        assertThat(isSensitivePropertyName("mockserver.authToken"), is(true));
    }

    @Test
    public void shouldDetectCredentialProperty() {
        assertThat(isSensitivePropertyName("mockserver.serviceCredential"), is(true));
    }

    @Test
    public void shouldDetectPassphraseProperty() {
        assertThat(isSensitivePropertyName("mockserver.keyPassphrase"), is(true));
    }

    @Test
    public void shouldDetectAccessUnderscoreKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_access_key_id"), is(true));
    }

    @Test
    public void shouldDetectApiUnderscoreKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_api_key"), is(true));
    }

    @Test
    public void shouldDetectConnectionUnderscoreStringProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_connection_string"), is(true));
    }

    @Test
    public void shouldDetectPrivateUnderscoreKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_private_key"), is(true));
    }

    // --- isSensitivePropertyName: works without mockserver. prefix ---

    @Test
    public void shouldDetectSensitiveWithoutPrefix() {
        assertThat(isSensitivePropertyName("llmApiKey"), is(true));
        assertThat(isSensitivePropertyName("blobStoreSecretAccessKey"), is(true));
        assertThat(isSensitivePropertyName("proxyAuthenticationPassword"), is(true));
    }

    // --- isSensitivePropertyName: case insensitive ---

    @Test
    public void shouldBeCaseInsensitive() {
        assertThat(isSensitivePropertyName("mockserver.LLMAPIKEY"), is(true));
        assertThat(isSensitivePropertyName("mockserver.BlobStoreSecretAccessKey"), is(true));
        assertThat(isSensitivePropertyName("MOCKSERVER.PASSWORD"), is(true));
    }

    // --- isSensitivePropertyName: negative cases ---

    @Test
    public void shouldNotFlagNonSensitiveProperties() {
        assertThat(isSensitivePropertyName("mockserver.logLevel"), is(false));
        assertThat(isSensitivePropertyName("mockserver.maxExpectations"), is(false));
        assertThat(isSensitivePropertyName("mockserver.nioEventLoopThreadCount"), is(false));
        assertThat(isSensitivePropertyName("mockserver.blobStoreBucket"), is(false));
        assertThat(isSensitivePropertyName("mockserver.blobStoreRegion"), is(false));
        assertThat(isSensitivePropertyName("mockserver.forwardProxyAuthenticationUsername"), is(false));
        assertThat(isSensitivePropertyName("mockserver.enableCORSForAPI"), is(false));
    }

    @Test
    public void shouldHandleNull() {
        assertThat(isSensitivePropertyName(null), is(false));
    }

    @Test
    public void shouldHandleEmptyString() {
        assertThat(isSensitivePropertyName(""), is(false));
    }

    // --- redaction constant ---

    @Test
    public void redactedValueShouldBeStars() {
        assertThat(REDACTED_VALUE, is("***REDACTED***"));
    }
}
