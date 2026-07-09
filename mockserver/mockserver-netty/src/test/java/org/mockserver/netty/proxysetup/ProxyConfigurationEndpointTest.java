package org.mockserver.netty.proxysetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.socket.tls.KeyAndCertificateFactory.PROXY_SETUP_CA_CERTIFICATE_FILE_NAME;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural coverage for the {@code /mockserver/proxyConfiguration} control-plane endpoint and the
 * always-on materialisation of the active CA to {@value PROXY_SETUP_CA_CERTIFICATE_FILE_NAME}.
 * <p>
 * Each test starts a server with an isolated {@link Configuration} (no global ConfigurationProperties
 * mutation) so it is safe to run alongside the rest of the suite.
 */
public class ProxyConfigurationEndpointTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void defaultStartupWritesBakedInCaAndEndpointReportsUsingDefaultCa() throws Exception {
        Configuration configuration = configuration()
            .proxySetup(false)
            .proxySetupLogging(true) // exercise the startup-write path into the temp dir
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)
            .directoryToSaveDynamicSSLCertificate(temporaryFolder.getRoot().getAbsolutePath());

        ClientAndServer mockServer = ClientAndServer.startClientAndServer(configuration);
        try {
            int port = mockServer.getPort();

            // with proxySetupLogging enabled, startup materialises the active CA to the stable filename
            File caFile = new File(temporaryFolder.getRoot(), PROXY_SETUP_CA_CERTIFICATE_FILE_NAME);
            assertThat("startup should write " + PROXY_SETUP_CA_CERTIFICATE_FILE_NAME, caFile.exists(), is(true));
            String onDiskPem = new String(Files.readAllBytes(caFile.toPath()), StandardCharsets.UTF_8);
            assertThat(onDiskPem, containsString("BEGIN CERTIFICATE"));
            assertThat(onDiskPem, not(containsString("PRIVATE KEY")));

            // JSON variant
            JsonNode json = getJson(port);
            assertThat(json.get("usingDefaultCa").asBoolean(), is(true));
            assertThat(json.get("warning").isNull(), is(false));
            assertThat(json.get("warning").asText(), containsString("only safe for isolated"));
            assertThat(json.get("httpsProxy").asText(), is("http://localhost:" + port));
            assertThat(json.get("caCertificatePath").asText(), containsString(PROXY_SETUP_CA_CERTIFICATE_FILE_NAME));
            assertThat(json.get("caCertificatePem").asText(), containsString("BEGIN CERTIFICATE"));
            assertThat(json.get("caCertificatePem").asText(), not(containsString("PRIVATE KEY")));
            assertThat(json.get("environmentVariables").get("unix").asText(), containsString("export HTTPS_PROXY="));
            assertThat(json.get("environmentVariables").get("powershell").asText(), containsString("$env:HTTPS_PROXY"));

            // text/plain copy-paste variant
            String text = getText(port);
            assertThat(text, containsString("MockServer proxy setup"));
            assertThat(text, containsString("WARNING:"));
        } finally {
            stopQuietly(mockServer);
        }
    }

    /**
     * Regression guard for the embedded-silent guarantee (a prior BLOCK): with proxySetupLogging at its
     * OFF default, an embedded ClientAndServer must write NOTHING at startup, yet the endpoint must still
     * materialise the CA on demand. proxySetupLogging is set explicitly to false (its default value) so
     * the test is deterministic regardless of any global state left by other tests in the same fork.
     */
    @Test
    public void embeddedDefaultWritesNothingAtStartupButEndpointMaterialisesOnDemand() throws Exception {
        File tempDir = temporaryFolder.newFolder("embedded");
        Configuration configuration = configuration()
            .proxySetupLogging(false) // false is the default — embedded ClientAndServer stays silent
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)
            .directoryToSaveDynamicSSLCertificate(tempDir.getAbsolutePath());

        ClientAndServer mockServer = ClientAndServer.startClientAndServer(configuration);
        try {
            File caFile = new File(tempDir, PROXY_SETUP_CA_CERTIFICATE_FILE_NAME);

            // no startup write when proxySetupLogging is off
            assertThat("startup must NOT write " + PROXY_SETUP_CA_CERTIFICATE_FILE_NAME + " when proxySetupLogging is off",
                caFile.exists(), is(false));

            // the endpoint still works and materialises the file on demand
            JsonNode json = getJson(mockServer.getPort());
            assertThat(json.get("usingDefaultCa").asBoolean(), is(true));
            assertThat(json.get("caCertificatePem").asText(), containsString("BEGIN CERTIFICATE"));
            assertThat(json.get("caCertificatePem").asText(), not(containsString("PRIVATE KEY")));
            assertThat("endpoint should materialise " + PROXY_SETUP_CA_CERTIFICATE_FILE_NAME + " on demand",
                caFile.exists(), is(true));
        } finally {
            stopQuietly(mockServer);
        }
    }

    @Test
    public void proxySetupGeneratesUniqueDynamicCaAndEndpointReportsNoWarning() throws Exception {
        Configuration defaultConfiguration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
        String bakedInCaPem;
        ClientAndServer defaultServer = ClientAndServer.startClientAndServer(
            defaultConfiguration.directoryToSaveDynamicSSLCertificate(temporaryFolder.newFolder("baked").getAbsolutePath()));
        try {
            bakedInCaPem = getJson(defaultServer.getPort()).get("caCertificatePem").asText();
        } finally {
            stopQuietly(defaultServer);
        }

        Configuration proxySetupConfiguration = configuration()
            .proxySetup(true)
            .directoryToSaveDynamicSSLCertificate(temporaryFolder.newFolder("dynamic").getAbsolutePath());

        ClientAndServer mockServer = ClientAndServer.startClientAndServer(proxySetupConfiguration);
        try {
            int port = mockServer.getPort();

            JsonNode json = getJson(port);
            assertThat(json.get("usingDefaultCa").asBoolean(), is(false));
            assertThat(json.get("warning").isNull(), is(true));
            assertThat(json.get("caCertificatePem").asText(), containsString("BEGIN CERTIFICATE"));
            assertThat(json.get("caCertificatePem").asText(), not(containsString("PRIVATE KEY")));

            // a unique private CA: the dynamic CA must differ from the public baked-in CA
            assertThat(json.get("caCertificatePem").asText(), is(not(bakedInCaPem)));

            String text = getText(port);
            assertThat(text, not(containsString("WARNING:")));
        } finally {
            stopQuietly(mockServer);
        }
    }

    private JsonNode getJson(int port) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mockserver/proxyConfiguration"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode(), is(200));
        return OBJECT_MAPPER.readTree(response.body());
    }

    private String getText(int port) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mockserver/proxyConfiguration"))
                .header("Accept", "text/plain")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode(), is(200));
        return response.body();
    }
}
