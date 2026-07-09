package org.mockserver.socket.tls;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

public class ProxySetupInfoTest {

    private static final String CA_PATH = "/tmp/mockserver-ca.pem";
    private static final String LINUX = "Linux";
    private static final String WINDOWS = "Windows 11";

    /**
     * Default (baked-in) CA: not dynamic, no custom CA supplied. These fields are set explicitly on the
     * instance so the assertions never depend on global ConfigurationProperties state.
     */
    private static Configuration defaultCaConfiguration() {
        return configuration()
            .proxySetup(false)
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
    }

    @Test
    public void shouldBuildHttpsProxyUrlFromFirstPort() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Arrays.asList(1090, 1091), defaultCaConfiguration(), LINUX);

        assertThat(info.httpsProxyUrl(), is("http://localhost:1090"));
    }

    @Test
    public void shouldFallBackToDefaultPortWhenNonePresent() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.emptyList(), defaultCaConfiguration(), LINUX);

        assertThat(info.httpsProxyUrl(), is("http://localhost:1080"));
    }

    @Test
    public void shouldFlagUsingDefaultCaAndProvideWarning() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), LINUX);

        assertThat(info.usingDefaultCa(), is(true));
        assertThat(info.warning(), is(notNullValue()));
        assertThat(info.warning(), containsString("only safe for isolated"));
    }

    @Test
    public void shouldNotFlagDefaultCaWhenDynamicCaEnabled() {
        Configuration configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true);

        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), configuration, LINUX);

        assertThat(info.usingDefaultCa(), is(false));
        assertThat(info.warning(), is(nullValue()));
    }

    @Test
    public void shouldNotFlagDefaultCaWhenProxySetupForcesDynamicCa() {
        Configuration configuration = configuration()
            .proxySetup(true)
            .dynamicallyCreateCertificateAuthorityCertificate(false);

        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), configuration, LINUX);

        assertThat(info.usingDefaultCa(), is(false));
        assertThat(info.warning(), is(nullValue()));
    }

    @Test
    public void shouldNotFlagDefaultCaWhenCustomCaSupplied() {
        Configuration configuration = configuration()
            .proxySetup(false)
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate("/custom/path/my-ca.pem");

        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), configuration, LINUX);

        assertThat(info.usingDefaultCa(), is(false));
        assertThat(info.warning(), is(nullValue()));
    }

    @Test
    public void shouldRenderUnixEnvBlockWithAllFourVariables() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), LINUX);

        String block = info.unixEnvBlock();
        assertThat(block, containsString("export HTTPS_PROXY=http://localhost:1080"));
        assertThat(block, containsString("export NODE_EXTRA_CA_CERTS=" + CA_PATH));
        assertThat(block, containsString("export SSL_CERT_FILE=" + CA_PATH));
        assertThat(block, containsString("export REQUESTS_CA_BUNDLE=" + CA_PATH));
    }

    @Test
    public void shouldRenderPowershellEnvBlockWithAllFourVariables() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), WINDOWS);

        String block = info.powershellEnvBlock();
        assertThat(block, containsString("$env:HTTPS_PROXY = \"http://localhost:1080\""));
        assertThat(block, containsString("$env:NODE_EXTRA_CA_CERTS = \"" + CA_PATH + "\""));
        assertThat(block, containsString("$env:SSL_CERT_FILE = \"" + CA_PATH + "\""));
        assertThat(block, containsString("$env:REQUESTS_CA_BUNDLE = \"" + CA_PATH + "\""));
    }

    @Test
    public void shouldSelectUnixBlockForNonWindowsOs() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), LINUX);

        assertThat(info.isWindows(), is(false));
        assertThat(info.copyPasteText(), containsString("export HTTPS_PROXY="));
        assertThat(info.copyPasteText(), not(containsString("$env:HTTPS_PROXY")));
    }

    @Test
    public void shouldSelectPowershellBlockForWindowsOs() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), WINDOWS);

        assertThat(info.isWindows(), is(true));
        assertThat(info.copyPasteText(), containsString("$env:HTTPS_PROXY ="));
        assertThat(info.copyPasteText(), not(containsString("export HTTPS_PROXY=")));
    }

    @Test
    public void shouldIncludeCaPathProxyUrlAndContainerNoteInCopyPasteText() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), LINUX);

        String text = info.copyPasteText();
        assertThat(text, containsString("CA certificate: " + CA_PATH));
        assertThat(text, containsString("HTTPS proxy:    http://localhost:1080"));
        assertThat(text, containsString("host.docker.internal"));
    }

    @Test
    public void shouldIncludeWarningInCopyPasteTextWhenUsingDefaultCa() {
        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), defaultCaConfiguration(), LINUX);

        assertThat(info.copyPasteText(), containsString("WARNING:"));
    }

    @Test
    public void shouldOmitWarningFromCopyPasteTextWhenUsingUniqueCa() {
        Configuration configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true);

        ProxySetupInfo info = new ProxySetupInfo(CA_PATH, Collections.singletonList(1080), configuration, LINUX);

        assertThat(info.copyPasteText(), not(containsString("WARNING:")));
    }
}
