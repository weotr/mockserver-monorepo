package org.mockserver.configuration;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.configuration.ClientConfiguration.clientConfiguration;
import static org.mockserver.configuration.Configuration.configuration;

public class ClientConfigurationTest {

    private ClientConfiguration clientConfiguration;

    @Before
    public void setUp() {
        clientConfiguration = new ClientConfiguration();
    }

    private String tempFilePath() {
        try {
            return File.createTempFile("prefix", "suffix").getAbsolutePath();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe.getMessage(), ioe);
        }
    }

    // factory methods

    @Test
    public void shouldCreateViaStaticFactoryMethod() {
        ClientConfiguration config = clientConfiguration();
        assertThat(config, is(notNullValue()));
    }

    @Test
    public void shouldCreateWithServerConfiguration() {
        String caChainPath = tempFilePath();
        String privateKeyPath = tempFilePath();
        String certPath = tempFilePath();
        String jwkPath = tempFilePath();

        Configuration serverConfig = configuration();
        serverConfig.webSocketClientEventLoopThreadCount(12);
        serverConfig.clientNioEventLoopThreadCount(14);
        serverConfig.maxSocketTimeoutInMillis(5000L);
        serverConfig.maxFutureTimeoutInMillis(8000L);
        serverConfig.controlPlaneTLSMutualAuthenticationRequired(true);
        serverConfig.controlPlaneTLSMutualAuthenticationCAChain(caChainPath);
        serverConfig.controlPlanePrivateKeyPath(privateKeyPath);
        serverConfig.controlPlaneX509CertificatePath(certPath);
        serverConfig.controlPlaneJWTAuthenticationRequired(true);
        serverConfig.controlPlaneJWTAuthenticationJWKSource(jwkPath);

        ClientConfiguration config = clientConfiguration(serverConfig);

        assertThat(config.webSocketClientEventLoopThreadCount(), is(12));
        assertThat(config.clientNioEventLoopThreadCount(), is(14));
        assertThat(config.maxSocketTimeoutInMillis(), is(5000L));
        assertThat(config.maxFutureTimeoutInMillis(), is(8000L));
        assertThat(config.controlPlaneTLSMutualAuthenticationRequired(), is(true));
        assertThat(config.controlPlaneTLSMutualAuthenticationCAChain(), is(caChainPath));
        assertThat(config.controlPlanePrivateKeyPath(), is(privateKeyPath));
        assertThat(config.controlPlaneX509CertificatePath(), is(certPath));
        assertThat(config.controlPlaneJWTAuthenticationRequired(), is(true));
        assertThat(config.controlPlaneJWTAuthenticationJWKSource(), is(jwkPath));
    }

    @Test
    public void shouldCreateWithNullServerConfiguration() {
        // should not throw when null is passed
        ClientConfiguration config = new ClientConfiguration(null);
        assertThat(config, is(notNullValue()));
    }

    // maxWebSocketExpectations

    @Test
    public void shouldReturnDefaultMaxWebSocketExpectations() {
        assertThat(clientConfiguration.maxWebSocketExpectations(), is(ConfigurationProperties.maxWebSocketExpectations()));
    }

    @Test
    public void shouldSetAndGetMaxWebSocketExpectations() {
        // when
        ClientConfiguration result = clientConfiguration.maxWebSocketExpectations(999);

        // then - returns this for fluent chaining
        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.maxWebSocketExpectations(), is(999));
    }

    // webSocketClientEventLoopThreadCount

    @Test
    public void shouldReturnDefaultWebSocketClientEventLoopThreadCount() {
        assertThat(clientConfiguration.webSocketClientEventLoopThreadCount(), is(ConfigurationProperties.webSocketClientEventLoopThreadCount()));
    }

    @Test
    public void shouldSetAndGetWebSocketClientEventLoopThreadCount() {
        ClientConfiguration result = clientConfiguration.webSocketClientEventLoopThreadCount(10);

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.webSocketClientEventLoopThreadCount(), is(10));
    }

    // clientNioEventLoopThreadCount

    @Test
    public void shouldReturnDefaultClientNioEventLoopThreadCount() {
        assertThat(clientConfiguration.clientNioEventLoopThreadCount(), is(ConfigurationProperties.clientNioEventLoopThreadCount()));
    }

    @Test
    public void shouldSetAndGetClientNioEventLoopThreadCount() {
        ClientConfiguration result = clientConfiguration.clientNioEventLoopThreadCount(8);

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.clientNioEventLoopThreadCount(), is(8));
    }

    // maxSocketTimeoutInMillis

    @Test
    public void shouldReturnDefaultMaxSocketTimeoutInMillis() {
        assertThat(clientConfiguration.maxSocketTimeoutInMillis(), is(ConfigurationProperties.maxSocketTimeout()));
    }

    @Test
    public void shouldSetAndGetMaxSocketTimeoutInMillis() {
        ClientConfiguration result = clientConfiguration.maxSocketTimeoutInMillis(30000L);

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.maxSocketTimeoutInMillis(), is(30000L));
    }

    // maxFutureTimeoutInMillis

    @Test
    public void shouldReturnDefaultMaxFutureTimeoutInMillis() {
        assertThat(clientConfiguration.maxFutureTimeoutInMillis(), is(ConfigurationProperties.maxFutureTimeout()));
    }

    @Test
    public void shouldSetAndGetMaxFutureTimeoutInMillis() {
        ClientConfiguration result = clientConfiguration.maxFutureTimeoutInMillis(120000L);

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.maxFutureTimeoutInMillis(), is(120000L));
    }

    // controlPlaneTLSMutualAuthenticationRequired

    @Test
    public void shouldReturnDefaultControlPlaneTLSMutualAuthenticationRequired() {
        assertThat(clientConfiguration.controlPlaneTLSMutualAuthenticationRequired(), is(ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired()));
    }

    @Test
    public void shouldSetAndGetControlPlaneTLSMutualAuthenticationRequired() {
        ClientConfiguration result = clientConfiguration.controlPlaneTLSMutualAuthenticationRequired(true);

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.controlPlaneTLSMutualAuthenticationRequired(), is(true));
    }

    // controlPlaneTLSMutualAuthenticationCAChain

    @Test
    public void shouldReturnDefaultControlPlaneTLSMutualAuthenticationCAChain() {
        assertThat(clientConfiguration.controlPlaneTLSMutualAuthenticationCAChain(), is(ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain()));
    }

    @Test
    public void shouldSetAndGetControlPlaneTLSMutualAuthenticationCAChain() {
        ClientConfiguration result = clientConfiguration.controlPlaneTLSMutualAuthenticationCAChain("/path/to/ca.pem");

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.controlPlaneTLSMutualAuthenticationCAChain(), is("/path/to/ca.pem"));
    }

    // controlPlanePrivateKeyPath

    @Test
    public void shouldReturnDefaultControlPlanePrivateKeyPath() {
        assertThat(clientConfiguration.controlPlanePrivateKeyPath(), is(ConfigurationProperties.controlPlanePrivateKeyPath()));
    }

    @Test
    public void shouldSetAndGetControlPlanePrivateKeyPath() {
        ClientConfiguration result = clientConfiguration.controlPlanePrivateKeyPath("/path/to/key.pem");

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.controlPlanePrivateKeyPath(), is("/path/to/key.pem"));
    }

    // controlPlaneX509CertificatePath

    @Test
    public void shouldReturnDefaultControlPlaneX509CertificatePath() {
        assertThat(clientConfiguration.controlPlaneX509CertificatePath(), is(ConfigurationProperties.controlPlaneX509CertificatePath()));
    }

    @Test
    public void shouldSetAndGetControlPlaneX509CertificatePath() {
        ClientConfiguration result = clientConfiguration.controlPlaneX509CertificatePath("/path/to/cert.pem");

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.controlPlaneX509CertificatePath(), is("/path/to/cert.pem"));
    }

    // controlPlaneJWTAuthenticationRequired

    @Test
    public void shouldReturnDefaultControlPlaneJWTAuthenticationRequired() {
        assertThat(clientConfiguration.controlPlaneJWTAuthenticationRequired(), is(ConfigurationProperties.controlPlaneJWTAuthenticationRequired()));
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationRequired() {
        ClientConfiguration result = clientConfiguration.controlPlaneJWTAuthenticationRequired(true);

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.controlPlaneJWTAuthenticationRequired(), is(true));
    }

    // controlPlaneJWTAuthenticationJWKSource

    @Test
    public void shouldReturnDefaultControlPlaneJWTAuthenticationJWKSource() {
        assertThat(clientConfiguration.controlPlaneJWTAuthenticationJWKSource(), is(ConfigurationProperties.controlPlaneJWTAuthenticationJWKSource()));
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationJWKSource() {
        ClientConfiguration result = clientConfiguration.controlPlaneJWTAuthenticationJWKSource("/path/to/jwk.json");

        assertThat(result, is(clientConfiguration));
        assertThat(clientConfiguration.controlPlaneJWTAuthenticationJWKSource(), is("/path/to/jwk.json"));
    }

    // toServerConfiguration

    @Test
    public void shouldConvertToServerConfiguration() {
        String caPath = tempFilePath();
        String keyPath = tempFilePath();
        String certPath = tempFilePath();
        String jwkPath = tempFilePath();

        clientConfiguration
            .webSocketClientEventLoopThreadCount(11)
            .clientNioEventLoopThreadCount(13)
            .maxSocketTimeoutInMillis(7000L)
            .maxFutureTimeoutInMillis(9000L)
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationCAChain(caPath)
            .controlPlanePrivateKeyPath(keyPath)
            .controlPlaneX509CertificatePath(certPath)
            .controlPlaneJWTAuthenticationRequired(true)
            .controlPlaneJWTAuthenticationJWKSource(jwkPath);

        Configuration serverConfig = clientConfiguration.toServerConfiguration();

        assertThat(serverConfig.webSocketClientEventLoopThreadCount(), is(11));
        assertThat(serverConfig.clientNioEventLoopThreadCount(), is(13));
        assertThat(serverConfig.maxSocketTimeoutInMillis(), is(7000L));
        assertThat(serverConfig.maxFutureTimeoutInMillis(), is(9000L));
        assertThat(serverConfig.controlPlaneTLSMutualAuthenticationRequired(), is(true));
        assertThat(serverConfig.controlPlaneTLSMutualAuthenticationCAChain(), is(caPath));
        assertThat(serverConfig.controlPlanePrivateKeyPath(), is(keyPath));
        assertThat(serverConfig.controlPlaneX509CertificatePath(), is(certPath));
        assertThat(serverConfig.controlPlaneJWTAuthenticationRequired(), is(true));
        assertThat(serverConfig.controlPlaneJWTAuthenticationJWKSource(), is(jwkPath));
    }

    @Test
    public void shouldConvertToServerConfigurationWhenCreatedWithServerConfig() {
        Configuration originalServerConfig = configuration();
        ClientConfiguration config = clientConfiguration(originalServerConfig);

        config.maxSocketTimeoutInMillis(42000L);

        Configuration result = config.toServerConfiguration();
        assertThat(result.maxSocketTimeoutInMillis(), is(42000L));
    }

    @Test
    public void shouldNotPropagateMaxWebSocketExpectationsToServerConfiguration() {
        // maxWebSocketExpectations is intentionally client-only: setting it on the
        // ClientConfiguration must NOT override the value the server-side Configuration
        // resolves from system properties / defaults.
        Integer serverDefault = ConfigurationProperties.maxWebSocketExpectations();
        ClientConfiguration config = clientConfiguration().maxWebSocketExpectations(999);

        Configuration serverConfig = config.toServerConfiguration();

        assertThat("client-side setter unaffected", config.maxWebSocketExpectations(), is(999));
        assertThat("server-side value falls back to default, not the client setting",
            serverConfig.maxWebSocketExpectations(), is(serverDefault));
    }

    // fluent chaining

    @Test
    public void shouldSupportFluentChaining() {
        ClientConfiguration result = clientConfiguration()
            .maxWebSocketExpectations(100)
            .webSocketClientEventLoopThreadCount(3)
            .clientNioEventLoopThreadCount(4)
            .maxSocketTimeoutInMillis(5000L)
            .maxFutureTimeoutInMillis(6000L)
            .controlPlaneTLSMutualAuthenticationRequired(false)
            .controlPlaneTLSMutualAuthenticationCAChain("")
            .controlPlanePrivateKeyPath("")
            .controlPlaneX509CertificatePath("")
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationJWKSource("");

        assertThat(result.maxWebSocketExpectations(), is(100));
        assertThat(result.webSocketClientEventLoopThreadCount(), is(3));
        assertThat(result.clientNioEventLoopThreadCount(), is(4));
        assertThat(result.maxSocketTimeoutInMillis(), is(5000L));
        assertThat(result.maxFutureTimeoutInMillis(), is(6000L));
    }
}
