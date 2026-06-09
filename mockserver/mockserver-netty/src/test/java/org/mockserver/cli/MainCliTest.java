package org.mockserver.cli;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;
import org.mockserver.version.Version;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Tests for the picocli-based CLI — subcommands, new flags, preprocessing, help, and version.
 */
public class MainCliTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(MainCliTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        Main.usageShown = false;
    }

    @After
    public void clearUsageShown() {
        Main.usageShown = false;
    }

    // ---- Preprocessing (bare → run) ----

    @Test
    public void shouldPrependRunWhenNoSubcommand() {
        String[] result = Main.preprocessArguments("-p", "1080");
        assertThat(result[0], is("run"));
        assertThat(result[1], is("-p"));
        assertThat(result[2], is("1080"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNotPrependRunWhenRunSubcommandPresent() {
        String[] result = Main.preprocessArguments("run", "-p", "1080");
        assertThat(result[0], is("run"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNotPrependRunWhenProxySubcommandPresent() {
        String[] result = Main.preprocessArguments("proxy", "--to", "localhost:8080");
        assertThat(result[0], is("proxy"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNotPrependRunWhenVersionSubcommandPresent() {
        String[] result = Main.preprocessArguments("version");
        assertThat(result[0], is("version"));
        assertThat(result.length, is(1));
    }

    @Test
    public void shouldNotPrependRunWhenOpenApiSubcommandPresent() {
        String[] result = Main.preprocessArguments("openapi", "./petstore.yaml", "-p", "1080");
        assertThat(result[0], is("openapi"));
        assertThat(result.length, is(4));
    }

    @Test
    public void shouldPrependRunForEmptyArgs() {
        String[] result = Main.preprocessArguments();
        assertThat(result[0], is("run"));
        assertThat(result.length, is(1));
    }

    @Test
    public void shouldPrependRunForLegacyFlags() {
        String[] result = Main.preprocessArguments("-serverPort", "1080", "-proxyRemotePort", "80");
        assertThat(result[0], is("run"));
        assertThat(result[1], is("-serverPort"));
        assertThat(result.length, is(5));
    }

    // ---- New --port flag via "run" subcommand ----

    @Test
    public void shouldStartWithNewPortFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("run", "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithLongPortFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("run", "--port", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithBarePortFlagNoSubcommand() {
        // "mockserver -p 1080" should work (bare → run)
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithNewLogLevelFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            Main.main("run", "-p", String.valueOf(freePort), "-l", "WARN");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("WARN"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithLongLogLevelFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            Main.main("run", "--port", String.valueOf(freePort), "--log-level", "DEBUG");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("DEBUG"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    // ---- Version subcommand ----

    @Test
    public void shouldPrintVersion() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("version");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            String expectedVersion = "MockServer " + Version.getVersion();
            assertThat("version subcommand output should match the --version output",
                output.trim(), is(expectedVersion));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- Help output ----

    @Test
    public void shouldPrintHelpForRunSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("--port"));
            assertThat(output, containsString("--proxy-to"));
            assertThat(output, containsString("--openapi"));
            assertThat(output, containsString("--init"));
            assertThat(output, containsString("--persist"));
            assertThat(output, containsString("--log-level"));
            assertThat(output, containsString("--dev"));
            // Legacy flags should be hidden
            assertThat(output, not(containsString("-serverPort")));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldPrintTopLevelHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("run"));
            assertThat(output, containsString("proxy"));
            assertThat(output, containsString("openapi"));
            assertThat(output, containsString("version"));
            assertThat(output, containsString("mock, proxy & record"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- OpenAPI --init and --persist wire into ConfigurationProperties ----

    @Test
    public void shouldSetOpenApiPathViaRunSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalOpenApiPath = ConfigurationProperties.initializationOpenAPIPath();
        File tempSpec = tempFolder.newFile("test-spec.yaml");

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--openapi", tempSpec.getAbsolutePath());
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.initializationOpenAPIPath(), is(tempSpec.getAbsolutePath()));
        } finally {
            ConfigurationProperties.initializationOpenAPIPath(originalOpenApiPath != null ? originalOpenApiPath : "");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetInitPathViaRunSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalInitPath = ConfigurationProperties.initializationJsonPath();
        File tempInit = tempFolder.newFile("test-init.json");

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--init", tempInit.getAbsolutePath());
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.initializationJsonPath(), is(tempInit.getAbsolutePath()));
        } finally {
            ConfigurationProperties.initializationJsonPath(originalInitPath != null ? originalInitPath : "");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetPersistPathViaRunSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        boolean originalPersist = ConfigurationProperties.persistExpectations();
        String originalPersistPath = ConfigurationProperties.persistedExpectationsPath();
        File tempPersist = tempFolder.newFile("test-persist.json");

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--persist", tempPersist.getAbsolutePath());
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.persistExpectations(), is(true));
            assertThat(ConfigurationProperties.persistedExpectationsPath(), is(tempPersist.getAbsolutePath()));
        } finally {
            ConfigurationProperties.persistExpectations(originalPersist);
            ConfigurationProperties.persistedExpectationsPath(originalPersistPath != null ? originalPersistPath : "");
            stopQuietly(mockServerClient);
        }
    }

    // ---- OpenAPI subcommand ----

    @Test
    public void shouldSetOpenApiPathViaOpenApiSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalOpenApiPath = ConfigurationProperties.initializationOpenAPIPath();
        File tempSpec = tempFolder.newFile("test-spec-sub.yaml");

        try {
            Main.main("openapi", tempSpec.getAbsolutePath(), "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.initializationOpenAPIPath(), is(tempSpec.getAbsolutePath()));
        } finally {
            ConfigurationProperties.initializationOpenAPIPath(originalOpenApiPath != null ? originalOpenApiPath : "");
            stopQuietly(mockServerClient);
        }
    }

    // ---- Legacy backward compat via picocli ----

    @Test
    public void shouldStartWithLegacyServerPortViaNewParser() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("-serverPort", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithLegacyFlagsAndLogLevel() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            Main.main("-serverPort", String.valueOf(freePort), "-logLevel", "DEBUG");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("DEBUG"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    // ---- Proxy-to scheme inference (fix 1) ----

    @Test
    public void shouldProxyToHttpsUrlInferringPort443ViaRunSubcommand() {
        final int freePort = PortFactory.findFreePort();
        EchoServer echoServer = new EchoServer(false);
        echoServer.withNextResponse(response("proxied_via_https_scheme"));
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            // Use the echo server port with an explicit port in the URL to verify proxy mode works,
            // but first verify parseProxyTarget resolves https:// → 443
            String[] parsed = Main.parseProxyTarget("https://api.example.com");
            assertThat("host from https URL", parsed[0], is("api.example.com"));
            assertThat("port inferred from https scheme", parsed[1], is("443"));

            // Now test that the actual proxy mode works end-to-end with a real echo server
            Main.main("run", "-p", String.valueOf(freePort),
                "--proxy-to", "http://127.0.0.1:" + echoServer.getPort());

            HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort),
                    10, TimeUnit.SECONDS
                );
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("response.getBodyAsString", httpResponse.getBodyAsString(), is("proxied_via_https_scheme"));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldProxyToHttpsUrlViaProxySubcommand() {
        final int freePort = PortFactory.findFreePort();
        EchoServer echoServer = new EchoServer(false);
        echoServer.withNextResponse(response("proxied_via_proxy_cmd"));
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("proxy", "--to", "http://127.0.0.1:" + echoServer.getPort(),
                "-p", String.valueOf(freePort));

            HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort),
                    10, TimeUnit.SECONDS
                );
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("response.getBodyAsString", httpResponse.getBodyAsString(), is("proxied_via_proxy_cmd"));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldParseProxyTargetWithHttpScheme() {
        String[] parsed = Main.parseProxyTarget("http://api.example.com");
        assertThat("host", parsed[0], is("api.example.com"));
        assertThat("port", parsed[1], is("80"));
    }

    @Test
    public void shouldParseProxyTargetWithHttpsSchemeAndPath() {
        String[] parsed = Main.parseProxyTarget("https://api.example.com/v2/path");
        assertThat("host", parsed[0], is("api.example.com"));
        assertThat("port", parsed[1], is("443"));
    }

    @Test
    public void shouldParseProxyTargetWithExplicitPortOverridingScheme() {
        String[] parsed = Main.parseProxyTarget("https://api.example.com:8443");
        assertThat("host", parsed[0], is("api.example.com"));
        assertThat("port", parsed[1], is("8443"));
    }

    @Test
    public void shouldParseProxyTargetHostColonPort() {
        String[] parsed = Main.parseProxyTarget("myhost:9090");
        assertThat("host", parsed[0], is("myhost"));
        assertThat("port", parsed[1], is("9090"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectProxyTargetWithNoPortAndNoScheme() {
        PrintStream originalOut = Main.systemOut;
        try {
            // Suppress the validation error box from polluting test output
            Main.systemOut = new PrintStream(new ByteArrayOutputStream(), true);
            Main.parseProxyTarget("api.example.com");
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldRejectProxyTargetWithNoPortProducesCleanError() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            try {
                Main.parseProxyTarget("api.example.com");
            } catch (IllegalArgumentException e) {
                // expected
            }

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("proxy target"));
            assertThat(output, containsString("has no port"));
            assertThat(output, containsString("--proxy-to api.example.com:8080"));
            assertThat(output, containsString("https://api.example.com"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldParseProxyTargetWithIPv6Brackets() {
        String[] parsed = Main.parseProxyTarget("[::1]:8080");
        assertThat("host", parsed[0], is("::1"));
        assertThat("port", parsed[1], is("8080"));
    }

    @Test
    public void shouldParseProxyTargetWithHttpsAndIPv6() {
        String[] parsed = Main.parseProxyTarget("https://[::1]");
        assertThat("host", parsed[0], is("::1"));
        assertThat("port", parsed[1], is("443"));
    }

    // ---- Help subcommand (fix 2) ----

    @Test
    public void shouldPrintHelpViaHelpSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("run"));
            assertThat(output, containsString("proxy"));
            assertThat(output, containsString("openapi"));
            assertThat(output, containsString("version"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldPrintHelpForSubcommandViaHelpSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help", "proxy");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("--to"));
            assertThat(output, containsString("--port"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- Picocli-era error paths show concise picocli usage, not legacy USAGE blob ----

    @Test
    public void shouldShowPicocliUsageForProxyToWithNoPort() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        PrintStream originalErr = Main.systemErr;
        try {
            ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(outBaos, true, StandardCharsets.UTF_8.name());
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--proxy-to", "api.example.com");

            String stdoutOutput = new String(outBaos.toByteArray(), StandardCharsets.UTF_8);
            // Should contain the actionable "has no port" error in the bordered box
            assertThat("should contain the 'has no port' validation message",
                stdoutOutput, containsString("has no port"));
            // Should contain picocli's concise usage (the run subcommand usage)
            assertThat("should contain picocli --port option in concise usage",
                stdoutOutput, containsString("--port"));
            assertThat("should contain picocli --proxy-to option in concise usage",
                stdoutOutput, containsString("--proxy-to"));
            // Should NOT contain the legacy USAGE blob
            assertThat("should not contain legacy 'java -jar <path to' blob",
                stdoutOutput, not(containsString("java -jar <path to")));
            assertThat("should not contain legacy '-serverPort <port>' line",
                stdoutOutput, not(containsString("-serverPort <port>")));
        } finally {
            Main.systemOut = originalOut;
            Main.systemErr = originalErr;
        }
    }

    @Test
    public void shouldShowPicocliUsageForUnknownFlag() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        PrintStream originalErr = Main.systemErr;
        try {
            ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(outBaos, true, StandardCharsets.UTF_8.name());
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--nonexistent-flag", "value");

            String stdoutOutput = new String(outBaos.toByteArray(), StandardCharsets.UTF_8);
            String stderrOutput = new String(errBaos.toByteArray(), StandardCharsets.UTF_8);
            String combinedOutput = stderrOutput + stdoutOutput;

            // Should contain the picocli error about the unknown option
            assertThat("should mention unknown option in error",
                stderrOutput, containsString("Unknown option"));
            // Should contain picocli's concise usage for the run subcommand
            assertThat("should contain picocli --port option in concise usage",
                stdoutOutput, containsString("--port"));
            assertThat("should contain picocli --proxy-to option in concise usage",
                stdoutOutput, containsString("--proxy-to"));
            // Should NOT contain the legacy USAGE blob
            assertThat("should not contain legacy 'java -jar <path to' blob",
                combinedOutput, not(containsString("java -jar <path to")));
            assertThat("should not contain legacy '-serverPort <port>' line",
                combinedOutput, not(containsString("-serverPort <port>")));
        } finally {
            Main.systemOut = originalOut;
            Main.systemErr = originalErr;
        }
    }

    // ---- Dev mode (--dev) ----

    @Test
    public void shouldApplyDevModeDefaults() throws Exception {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            // Clear any explicitly-set maxLogEntries/maxExpectations (cache + system property) that a prior or
            // parallel test may have leaked into the shared static ConfigurationProperties state. Dev mode only
            // applies its defaults to properties the user has NOT explicitly set, so a leaked value would make
            // the assertions below non-deterministic.
            java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cache = (java.util.Map<String, String>) cacheField.get(null);
            cache.remove("mockserver.maxLogEntries");
            System.clearProperty("mockserver.maxLogEntries");
            cache.remove("mockserver.maxExpectations");
            System.clearProperty("mockserver.maxExpectations");

            Main.main("run", "-p", String.valueOf(freePort), "--dev");

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("devMode should be enabled", ConfigurationProperties.devMode(), is(true));
            assertThat("maxLogEntries should be dev default (1000)",
                ConfigurationProperties.maxLogEntries(), is(1000));
            assertThat("maxExpectations should be dev default (1000)",
                ConfigurationProperties.maxExpectations(), is(1000));
        } finally {
            // Restore production defaults
            ConfigurationProperties.devMode(false);
            // Force maxLogEntries and maxExpectations back to a heap-based value
            // by setting them to a known large value (the static cache persists across tests)
            int heapBasedMaxLogEntries = Math.min((int) (ConfigurationProperties.heapAvailableInKB() / 8), 100000);
            int heapBasedMaxExpectations = Math.min((int) (ConfigurationProperties.heapAvailableInKB() / 10), 15000);
            ConfigurationProperties.maxLogEntries(heapBasedMaxLogEntries);
            ConfigurationProperties.maxExpectations(heapBasedMaxExpectations);
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldNotApplyDevModeWithoutFlag() {
        // Verify that without --dev, the devMode property is false.
        // We cannot reliably assert exact maxLogEntries/maxExpectations values because
        // the property cache is shared across test methods; instead we verify the flag.
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("run", "-p", String.valueOf(freePort));

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("devMode should be false by default", ConfigurationProperties.devMode(), is(false));
        } finally {
            stopQuietly(mockServerClient);
        }
    }
}
