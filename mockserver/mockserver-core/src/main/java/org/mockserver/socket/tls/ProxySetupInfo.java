package org.mockserver.socket.tls;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import java.util.List;

/**
 * Single source of truth for the copy-paste proxy-setup information surfaced both in the startup log
 * (see {@code LifeCycle.startedServer}) and the {@code /mockserver/proxyConfiguration} control-plane
 * endpoint. It carries the active Certificate Authority certificate path, the HTTPS proxy URL, and the
 * OS-specific environment-variable blocks a client needs to route TLS traffic through MockServer and
 * trust its CA.
 * <p>
 * This is a pure value holder — constructing it has no logging or filesystem side effects. The CA file
 * is materialised separately via {@link KeyAndCertificateFactory#writeCertificateAuthorityToDisk()} and
 * its absolute path passed in.
 *
 * @author jamesdbloom
 */
public class ProxySetupInfo {

    public static final String DEFAULT_CA_WARNING =
        "The default CA private key is published in the MockServer repository — only safe for isolated " +
            "local development. Re-run with --proxy-setup (or " +
            "-Dmockserver.dynamicallyCreateCertificateAuthorityCertificate=true) to generate a unique private CA.";

    private static final String CONTAINER_NOTE =
        "Note: containerised clients must replace \"localhost\" with a host reachable from the container " +
            "(e.g. host.docker.internal).";

    private final String caCertificatePath;
    private final String httpsProxyUrl;
    private final boolean usingDefaultCa;
    private final String warning;
    private final boolean windows;

    /**
     * @param caCertificatePath absolute path of the materialised active CA certificate PEM
     * @param ports             the bound port(s); the first is used for the proxy URL
     * @param configuration     the active configuration (used to determine the CA in effect)
     * @param osName            the OS name (e.g. {@code System.getProperty("os.name")}) used to select
     *                          between the Unix and PowerShell environment-variable blocks
     */
    public ProxySetupInfo(String caCertificatePath, List<Integer> ports, Configuration configuration, String osName) {
        this.caCertificatePath = caCertificatePath;
        int port = (ports == null || ports.isEmpty() || ports.get(0) == null) ? 1080 : ports.get(0);
        this.httpsProxyUrl = "http://localhost:" + port;
        this.usingDefaultCa = isUsingDefaultCa(configuration);
        this.warning = usingDefaultCa ? DEFAULT_CA_WARNING : null;
        this.windows = osName != null && osName.toLowerCase().contains("win");
    }

    /**
     * The public baked-in CA is in effect only when neither dynamic CA generation (which
     * {@code proxySetup} forces on) is enabled nor a custom CA certificate has been supplied.
     */
    private static boolean isUsingDefaultCa(Configuration configuration) {
        boolean dynamic = Boolean.TRUE.equals(configuration.dynamicallyCreateCertificateAuthorityCertificate());
        boolean customCa = !ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE
            .equals(configuration.certificateAuthorityCertificate());
        return !dynamic && !customCa;
    }

    public String caCertificatePath() {
        return caCertificatePath;
    }

    public String httpsProxyUrl() {
        return httpsProxyUrl;
    }

    public boolean usingDefaultCa() {
        return usingDefaultCa;
    }

    /**
     * @return the security warning text when the public baked-in CA is in effect, otherwise {@code null}
     */
    public String warning() {
        return warning;
    }

    public boolean isWindows() {
        return windows;
    }

    /**
     * Bash / zsh environment-variable exports for routing TLS traffic through MockServer and trusting
     * its CA.
     */
    public String unixEnvBlock() {
        return "export HTTPS_PROXY=" + httpsProxyUrl + "\n" +
            "export NODE_EXTRA_CA_CERTS=" + caCertificatePath + "\n" +
            "export SSL_CERT_FILE=" + caCertificatePath + "\n" +
            "export REQUESTS_CA_BUNDLE=" + caCertificatePath;
    }

    /**
     * PowerShell environment-variable assignments equivalent to {@link #unixEnvBlock()}.
     */
    public String powershellEnvBlock() {
        return "$env:HTTPS_PROXY = \"" + httpsProxyUrl + "\"\n" +
            "$env:NODE_EXTRA_CA_CERTS = \"" + caCertificatePath + "\"\n" +
            "$env:SSL_CERT_FILE = \"" + caCertificatePath + "\"\n" +
            "$env:REQUESTS_CA_BUNDLE = \"" + caCertificatePath + "\"";
    }

    /**
     * Assembles the full human-readable copy-paste block for the current OS, selecting the PowerShell
     * block on Windows and the Unix block otherwise, and appending the security warning when the public
     * baked-in CA is in effect.
     */
    public String copyPasteText() {
        StringBuilder builder = new StringBuilder();
        builder.append("MockServer proxy setup\n");
        builder.append("======================\n");
        builder.append("CA certificate: ").append(caCertificatePath).append("\n");
        builder.append("HTTPS proxy:    ").append(httpsProxyUrl).append("\n");
        builder.append("\n");
        builder.append(windows ? powershellEnvBlock() : unixEnvBlock()).append("\n");
        builder.append("\n");
        builder.append(CONTAINER_NOTE);
        if (warning != null) {
            builder.append("\n\nWARNING: ").append(warning);
        }
        return builder.toString();
    }
}
