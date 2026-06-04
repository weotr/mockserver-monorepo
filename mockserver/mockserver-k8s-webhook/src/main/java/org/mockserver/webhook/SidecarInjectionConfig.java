package org.mockserver.webhook;

/**
 * Configuration for the MockServer sidecar injection.
 * All fields have sensible defaults matching the Helm chart's sidecar values.
 */
public class SidecarInjectionConfig {

    // Annotation keys
    public static final String INJECT_ANNOTATION = "mockserver.org/inject";
    public static final String INJECTED_ANNOTATION = "mockserver.org/injected";
    public static final String STATUS_ANNOTATION = "mockserver.org/status";

    // Annotation values
    public static final String INJECT_ENABLED = "true";
    public static final String INJECTED_VALUE = "true";

    /**
     * Default MockServer sidecar image. The version suffix is an override-me default;
     * in the Helm chart the image is passed via the MOCKSERVER_IMAGE env var, so this
     * value only applies when running outside Helm or when no override is provided.
     */
    private String mockserverImage = "mockserver/mockserver:mockserver-6.1.1-SNAPSHOT";
    private String iptablesImage = "alpine:3.19";
    private int serverPort = 1080;
    private String redirectPorts = "80,443";
    private int runAsUser = 65534;
    private String logLevel = "INFO";

    public String getMockserverImage() {
        return mockserverImage;
    }

    public SidecarInjectionConfig setMockserverImage(String mockserverImage) {
        this.mockserverImage = mockserverImage;
        return this;
    }

    public String getIptablesImage() {
        return iptablesImage;
    }

    public SidecarInjectionConfig setIptablesImage(String iptablesImage) {
        this.iptablesImage = iptablesImage;
        return this;
    }

    public int getServerPort() {
        return serverPort;
    }

    public SidecarInjectionConfig setServerPort(int serverPort) {
        this.serverPort = serverPort;
        return this;
    }

    public String getRedirectPorts() {
        return redirectPorts;
    }

    public SidecarInjectionConfig setRedirectPorts(String redirectPorts) {
        this.redirectPorts = redirectPorts;
        return this;
    }

    public int getRunAsUser() {
        return runAsUser;
    }

    public SidecarInjectionConfig setRunAsUser(int runAsUser) {
        this.runAsUser = runAsUser;
        return this;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public SidecarInjectionConfig setLogLevel(String logLevel) {
        this.logLevel = logLevel;
        return this;
    }
}
