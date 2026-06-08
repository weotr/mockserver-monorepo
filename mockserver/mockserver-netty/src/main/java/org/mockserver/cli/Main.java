package org.mockserver.cli;

import com.google.common.base.Joiner;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.configuration.IntegerStringListParser;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.version.Version;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.HttpState.setPort;
import static org.slf4j.event.Level.*;

/**
 * @author jamesdbloom
 */
@Command(
    name = "mockserver",
    mixinStandardHelpOptions = true,
    versionProvider = Main.MockServerVersionProvider.class,
    description = "MockServer — mock, proxy & record HTTP(S), gRPC, and more.",
    footer = {
        "",
        "Examples:",
        "  mockserver run -p 1080",
        "  mockserver proxy --to https://api.example.com",
        "  mockserver openapi ./petstore.yaml -p 1080",
        "  mockserver -p 1080",
        "",
        "Legacy flags (-serverPort, -proxyRemotePort, -proxyRemoteHost, -logLevel) are supported for backward compatibility."
    },
    subcommands = {
        Main.RunCommand.class,
        Main.ProxyCommand.class,
        Main.OpenApiCommand.class,
        Main.VersionCommand.class,
        CommandLine.HelpCommand.class,
    }
)
public class Main {

    // Kept for backward compatibility with tests that reference Main.USAGE
    static final String USAGE = "" +
        "   version: " + Version.getVersion() + NEW_LINE +
        "    " + NEW_LINE +
        "   java -jar <path to mockserver-netty-jar-with-dependencies.jar> -serverPort <port> [-proxyRemotePort <port>] [-proxyRemoteHost <hostname>] [-logLevel <level>] " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "     valid options are:                                                                                                                                          " + NEW_LINE +
        "        -serverPort <port>           The HTTP, HTTPS, SOCKS and HTTP CONNECT                                                                                     " + NEW_LINE +
        "                                     port(s) for both mocking and proxying                                                                                       " + NEW_LINE +
        "                                     requests.  Port unification is used to                                                                                      " + NEW_LINE +
        "                                     support all protocols for proxying and                                                                                       " + NEW_LINE +
        "                                     mocking on the same port(s). Supports                                                                                       " + NEW_LINE +
        "                                     comma separated list for binding to                                                                                         " + NEW_LINE +
        "                                     multiple ports.                                                                                                             " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "        -proxyRemotePort <port>      Optionally enables port forwarding mode.                                                                                    " + NEW_LINE +
        "                                     When specified all requests received will                                                                                   " + NEW_LINE +
        "                                     be forwarded to the specified port, unless                                                                                  " + NEW_LINE +
        "                                     they match an expectation.                                                                                                  " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "        -proxyRemoteHost <hostname>  Specified the host to forward all proxy                                                                                     " + NEW_LINE +
        "                                     requests to when port forwarding mode has                                                                                   " + NEW_LINE +
        "                                     been enabled using the proxyRemotePort                                                                                      " + NEW_LINE +
        "                                     option.  This setting is ignored unless                                                                                     " + NEW_LINE +
        "                                     proxyRemotePort has been specified. If no                                                                                   " + NEW_LINE +
        "                                     value is provided for proxyRemoteHost when                                                                                  " + NEW_LINE +
        "                                     proxyRemotePort has been specified,                                                                                         " + NEW_LINE +
        "                                     proxyRemoteHost will default to \"localhost\".                                                                              " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "        -logLevel <level>            Optionally specify log level using SLF4J levels:                                                                            " + NEW_LINE +
        "                                     TRACE, DEBUG, INFO, WARN, ERROR, OFF or Java                                                                                " + NEW_LINE +
        "                                     Logger levels: FINEST, FINE, INFO, WARNING,                                                                                 " + NEW_LINE +
        "                                     SEVERE or OFF. If not specified default is INFO                                                                             " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "   i.e. java -jar ./mockserver-netty-jar-with-dependencies.jar -serverPort 1080 -proxyRemotePort 80 -proxyRemoteHost www.mock-server.com -logLevel WARN                         " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE;
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(Main.class);
    private static final IntegerStringListParser INTEGER_STRING_LIST_PARSER = new IntegerStringListParser();
    static PrintStream systemErr = System.err;
    static PrintStream systemOut = System.out;
    static boolean usageShown = false;

    /**
     * Run the MockServer directly providing the arguments as specified below.
     *
     * @param arguments the entries are in pairs:
     *                  - "-serverPort"       followed by the mandatory server local port,
     *                  - "-proxyRemotePort"  followed by the optional proxyRemotePort port that enabled port forwarding mode,
     *                  - "-proxyRemoteHost"  followed by the optional proxyRemoteHost port (ignored unless proxyRemotePort is specified)
     *                  - "-logLevel"         followed by the log level
     */
    public static void main(String... arguments) {
        try {
            // Preprocess: if the first non-option argument is not a known subcommand, prepend "run"
            String[] processedArgs = preprocessArguments(arguments);

            CommandLine cmd = new CommandLine(new Main());
            cmd.setOut(new java.io.PrintWriter(systemOut, true));
            cmd.setErr(new java.io.PrintWriter(systemErr, true));
            cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(ERROR)
                        .setMessageFormat("exception while starting:{}")
                        .setThrowable(ex)
                );
                showUsage(null);
                if (ConfigurationProperties.disableSystemOut()) {
                    new RuntimeException("exception while starting: " + ex.getMessage()).printStackTrace(System.err);
                }
                return 1;
            });
            cmd.setParameterExceptionHandler((ex, args) -> {
                // Print the error message + picocli's concise usage for the offending (sub)command
                systemErr.println("ERROR: " + ex.getMessage());
                systemErr.flush();
                systemOut.print(ex.getCommandLine().getUsageMessage());
                systemOut.flush();
                return 2;
            });
            cmd.execute(processedArgs);
        } catch (Throwable throwable) {
            MOCK_SERVER_LOGGER.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(ERROR)
                    .setMessageFormat("exception while starting:{}")
                    .setThrowable(throwable)
            );
            showUsage(null);
            if (ConfigurationProperties.disableSystemOut()) {
                new RuntimeException("exception while starting: " + throwable.getMessage()).printStackTrace(System.err);
            }
        }
    }

    /**
     * Preprocess arguments: if the first non-help/version token is not a known subcommand,
     * prepend "run" so that bare "mockserver -p 1080" and legacy "-serverPort 1080" work.
     */
    static String[] preprocessArguments(String... arguments) {
        if (arguments == null || arguments.length == 0) {
            return new String[]{"run"};
        }
        Set<String> subcommands = Set.of("run", "proxy", "openapi", "version", "help");
        // Top-level help/version flags should NOT be prepended with "run"
        Set<String> topLevelFlags = Set.of("--help", "-h", "--version", "-V");
        String first = arguments[0];
        // If the first token is a known subcommand, leave it alone
        if (subcommands.contains(first)) {
            return arguments;
        }
        // If the first token is a top-level help/version flag, leave it alone
        if (topLevelFlags.contains(first)) {
            return arguments;
        }
        // Otherwise it's an option or positional arg → prepend "run"
        String[] result = new String[arguments.length + 1];
        result[0] = "run";
        System.arraycopy(arguments, 0, result, 1, arguments.length);
        return result;
    }

    /**
     * Resolve configuration from CLI args, system properties, environment variables,
     * and properties file following the existing precedence:
     * CLI > system property > env var (long MOCKSERVER_* then short) > properties file.
     *
     * Then start the MockServer.
     */
    static void startServer(String serverPortValue, String proxyRemotePortValue,
                            String proxyRemoteHostValue, String logLevelValue) {
        Map<String, String> parsedArguments = new HashMap<>();
        Map<String, String> commandLineArguments = new HashMap<>();
        Map<String, String> environmentVariableArguments = new HashMap<>();
        Map<String, String> systemPropertyArguments = new HashMap<>();

        if (isNotBlank(serverPortValue)) {
            parsedArguments.put(Arguments.serverPort.name(), serverPortValue);
            commandLineArguments.put(Arguments.serverPort.name(), serverPortValue);
        }
        if (isNotBlank(proxyRemotePortValue)) {
            parsedArguments.put(Arguments.proxyRemotePort.name(), proxyRemotePortValue);
            commandLineArguments.put(Arguments.proxyRemotePort.name(), proxyRemotePortValue);
        }
        if (isNotBlank(proxyRemoteHostValue)) {
            parsedArguments.put(Arguments.proxyRemoteHost.name(), proxyRemoteHostValue);
            commandLineArguments.put(Arguments.proxyRemoteHost.name(), proxyRemoteHostValue);
        }
        if (isNotBlank(logLevelValue)) {
            parsedArguments.put(Arguments.logLevel.name(), logLevelValue);
            commandLineArguments.put(Arguments.logLevel.name(), logLevelValue);
        }

        System.getenv().forEach((key, value) -> {
            if (key.startsWith("MOCKSERVER_") && isNotBlank(value)) {
                environmentVariableArguments.put(key, value);
            }
        });
        System.getProperties().forEach((key, value) -> {
            if (key instanceof String && value instanceof String) {
                if (((String) key).startsWith("mockserver") && isNotBlank((String) value)) {
                    systemPropertyArguments.put((String) key, (String) value);
                }
            }
        });

        for (Arguments parsedArgument : Arrays.asList(Arguments.serverPort, Arguments.proxyRemoteHost, Arguments.proxyRemotePort)) {
            if (!parsedArguments.containsKey(parsedArgument.name())) {
                if (systemPropertyArguments.containsKey(parsedArgument.systemPropertyName())) {
                    parsedArguments.put(parsedArgument.name(), systemPropertyArguments.get(parsedArgument.systemPropertyName()));
                    environmentVariableArguments.remove(parsedArgument.longEnvironmentVariableName());
                    environmentVariableArguments.remove(parsedArgument.shortEnvironmentVariableName());
                } else {
                    if (environmentVariableArguments.containsKey(parsedArgument.longEnvironmentVariableName())) {
                        environmentVariableArguments.remove(parsedArgument.shortEnvironmentVariableName());
                        parsedArguments.put(parsedArgument.name(), environmentVariableArguments.get(parsedArgument.longEnvironmentVariableName()));
                    } else if (isNotBlank(System.getenv(parsedArgument.shortEnvironmentVariableName()))) {
                        if (!(parsedArgument == Arguments.serverPort && "1080".equals(System.getenv(Arguments.serverPort.shortEnvironmentVariableName())) && ConfigurationProperties.PROPERTIES.containsKey(Arguments.serverPort.systemPropertyName()))) {
                            environmentVariableArguments.put(parsedArgument.shortEnvironmentVariableName(), System.getenv(parsedArgument.shortEnvironmentVariableName()));
                            parsedArguments.put(parsedArgument.name(), environmentVariableArguments.get(parsedArgument.shortEnvironmentVariableName()));
                        }
                    }
                }
            } else {
                systemPropertyArguments.remove(parsedArgument.systemPropertyName());
                environmentVariableArguments.remove(parsedArgument.longEnvironmentVariableName());
                environmentVariableArguments.remove(parsedArgument.shortEnvironmentVariableName());
            }
            if (!parsedArguments.containsKey(parsedArgument.name()) && ConfigurationProperties.PROPERTIES.containsKey(parsedArgument.systemPropertyName())) {
                parsedArguments.put(parsedArgument.name(), String.valueOf(ConfigurationProperties.PROPERTIES.get(parsedArgument.systemPropertyName())));
            }
        }

        if (MockServerLogger.isEnabled(INFO)) {
            MOCK_SERVER_LOGGER.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(INFO)
                    .setMessageFormat("using environment variables:{}and system properties:{}and command line options:{}")
                    .setArguments(
                        "[\n\t" + Joiner.on(",\n\t").withKeyValueSeparator("=").join(environmentVariableArguments) + "\n]",
                        "[\n\t" + Joiner.on(",\n\t").withKeyValueSeparator("=").join(systemPropertyArguments) + "\n]",
                        "[\n\t" + Joiner.on(",\n\t").withKeyValueSeparator("=").join(commandLineArguments) + "\n]"
                    )
            );
        }

        if (parsedArguments.size() > 0 && parsedArguments.containsKey(Arguments.serverPort.name())) {
            if (parsedArguments.containsKey(Arguments.logLevel.name())) {
                ConfigurationProperties.logLevel(parsedArguments.get(Arguments.logLevel.name()));
            }
            Integer[] localPorts = INTEGER_STRING_LIST_PARSER.toArray(parsedArguments.get(Arguments.serverPort.name()));
            if (parsedArguments.containsKey(Arguments.proxyRemotePort.name())) {
                String remoteHost = parsedArguments.get(Arguments.proxyRemoteHost.name());
                if (isBlank(remoteHost)) {
                    remoteHost = "localhost";
                }
                new MockServer(Integer.parseInt(parsedArguments.get(Arguments.proxyRemotePort.name())), remoteHost, localPorts);
            } else {
                new MockServer(localPorts);
            }
            setPort(localPorts);

            if (ConfigurationProperties.logLevel() != null) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(ConfigurationProperties.logLevel())
                        .setMessageFormat("logger level is " + ConfigurationProperties.logLevel() + ", change using:\n - 'ConfigurationProperties.logLevel(String level)' in Java code,\n - '-logLevel' command line argument,\n - 'mockserver.logLevel' JVM system property or,\n - 'mockserver.logLevel' property value in 'mockserver.properties'")
                );
            }
        } else {
            showUsage("\"" + Arguments.serverPort.name() + "\" not specified");
        }
    }

    static void showUsage(String errorMessage) {
        if (!usageShown) {
            usageShown = true;
            systemOut.print(USAGE);
            systemOut.flush();
        }
        if (isNotBlank(errorMessage)) {
            systemErr.print("\nERROR:  " + errorMessage + "\n\n");
            systemErr.flush();
        }
    }

    // ---- Subcommands ----

    @Command(
        name = "run",
        description = "Start MockServer (default subcommand).",
        mixinStandardHelpOptions = true
    )
    static class RunCommand implements Runnable {

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list, e.g. 1080,1081).")
        String port;

        @Option(names = "--proxy-to", description = "Forward unmatched requests to host[:port] (enables port-forwarding mode).")
        String proxyTo;

        @Option(names = "--openapi", description = "Initialize expectations from an OpenAPI spec URL or file path.")
        String openapi;

        @Option(names = "--init", description = "Initialize expectations from a JSON file path or glob pattern.")
        String init;

        @Option(names = "--persist", description = "Enable expectation persistence and set the file path.")
        String persist;

        @Option(names = {"-l", "--log-level"}, description = "Log level: TRACE, DEBUG, INFO, WARN, ERROR, OFF (or Java Logger equivalents).")
        String logLevel;

        // -- reserved for future unit E6 --
        // @Option(names = "--dev") boolean dev;

        // Legacy hidden flags — exact single-token names so picocli matches them as long options
        @Option(names = "-serverPort", hidden = true)
        String legacyServerPort;

        @Option(names = "-proxyRemotePort", hidden = true)
        String legacyProxyRemotePort;

        @Option(names = "-proxyRemoteHost", hidden = true)
        String legacyProxyRemoteHost;

        @Option(names = "-logLevel", hidden = true)
        String legacyLogLevel;

        @Override
        public void run() {
            try {
                // Merge new flags with legacy flags (new flags take precedence)
                String resolvedPort = isNotBlank(port) ? port : legacyServerPort;
                String resolvedLogLevel = isNotBlank(logLevel) ? logLevel : legacyLogLevel;

                String resolvedProxyRemotePort = legacyProxyRemotePort;
                String resolvedProxyRemoteHost = legacyProxyRemoteHost;

                if (isNotBlank(proxyTo)) {
                    try {
                        String[] parsed = parseProxyTarget(proxyTo);
                        resolvedProxyRemoteHost = parsed[0];
                        resolvedProxyRemotePort = parsed[1];
                    } catch (IllegalArgumentException proxyTargetEx) {
                        // Validation error already printed by parseProxyTarget (bordered box).
                        // Show picocli's concise usage for the "run" command, not the legacy blob.
                        systemOut.print(new CommandLine(new RunCommand()).getUsageMessage());
                        systemOut.flush();
                        return;
                    }
                }

                // Wire --openapi
                if (isNotBlank(openapi)) {
                    ConfigurationProperties.initializationOpenAPIPath(openapi);
                }

                // Wire --init
                if (isNotBlank(init)) {
                    ConfigurationProperties.initializationJsonPath(init);
                }

                // Wire --persist
                if (isNotBlank(persist)) {
                    ConfigurationProperties.persistExpectations(true);
                    ConfigurationProperties.persistedExpectationsPath(persist);
                }

                // Validate legacy arguments inline (matching old behavior for error messages)
                List<String> errorMessages = validateArguments(resolvedPort, resolvedProxyRemotePort,
                    resolvedProxyRemoteHost, resolvedLogLevel);
                if (!errorMessages.isEmpty()) {
                    printValidationError(errorMessages);
                    throw new IllegalArgumentException(errorMessages.toString());
                }

                startServer(resolvedPort, resolvedProxyRemotePort, resolvedProxyRemoteHost, resolvedLogLevel);
            } catch (IllegalArgumentException e) {
                // Already handled — validation errors printed and usage shown via startServer
                showUsage(null);
            } catch (Throwable throwable) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(ERROR)
                        .setMessageFormat("exception while starting:{}")
                        .setThrowable(throwable)
                );
                showUsage(null);
                if (ConfigurationProperties.disableSystemOut()) {
                    new RuntimeException("exception while starting: " + throwable.getMessage()).printStackTrace(System.err);
                }
            }
        }
    }

    @Command(
        name = "proxy",
        description = "Start MockServer in port-forwarding (proxy) mode.",
        mixinStandardHelpOptions = true
    )
    static class ProxyCommand implements Runnable {

        @Option(names = "--to", required = true, description = "Forward unmatched requests to host[:port].")
        String to;

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list).")
        String port;

        @Option(names = {"-l", "--log-level"}, description = "Log level.")
        String logLevel;

        @Override
        public void run() {
            // Delegate to RunCommand logic by building equivalent args
            RunCommand runCmd = new RunCommand();
            runCmd.port = port;
            runCmd.proxyTo = to;
            runCmd.logLevel = logLevel;
            runCmd.run();
        }
    }

    @Command(
        name = "openapi",
        description = "Start MockServer and initialize expectations from an OpenAPI spec.",
        mixinStandardHelpOptions = true
    )
    static class OpenApiCommand implements Runnable {

        @Parameters(index = "0", description = "OpenAPI spec URL or file path.")
        String specPath;

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list).")
        String port;

        @Option(names = {"-l", "--log-level"}, description = "Log level.")
        String logLevel;

        @Override
        public void run() {
            RunCommand runCmd = new RunCommand();
            runCmd.port = port;
            runCmd.openapi = specPath;
            runCmd.logLevel = logLevel;
            runCmd.run();
        }
    }

    @Command(
        name = "version",
        description = "Print MockServer version and exit."
    )
    static class VersionCommand implements Runnable {
        @Override
        public void run() {
            systemOut.println("MockServer " + Version.getVersion());
        }
    }

    // ---- Version Provider ----

    static class MockServerVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"MockServer " + Version.getVersion()};
        }
    }

    // ---- Proxy target parsing ----

    /**
     * Parse a proxy target value (from --proxy-to or --to) into [host, port].
     * Accepts:
     *   host:port           → literal host and port
     *   https://host        → host, port 443
     *   http://host         → host, port 80
     *   https://host:port   → host, port (explicit overrides scheme default)
     *   http://host/path    → host, port 80 (path stripped)
     *
     * Rejects values with neither a scheme nor an explicit port.
     */
    static String[] parseProxyTarget(String value) {
        String raw = value.trim();
        String schemeDefaultPort = null;

        // Extract and strip scheme
        if (raw.startsWith("https://")) {
            schemeDefaultPort = "443";
            raw = raw.substring("https://".length());
        } else if (raw.startsWith("http://")) {
            schemeDefaultPort = "80";
            raw = raw.substring("http://".length());
        }

        // Strip trailing path (everything from the first '/')
        int slashIndex = raw.indexOf('/');
        if (slashIndex >= 0) {
            raw = raw.substring(0, slashIndex);
        }

        // Now raw is either "host", "host:port", or "[ipv6]:port"
        String host;
        String port = null;

        if (raw.startsWith("[")) {
            // IPv6 bracket notation: [::1]:port or [::1]
            int closeBracket = raw.indexOf(']');
            if (closeBracket < 0) {
                throw new IllegalArgumentException(
                    "invalid proxy target \"" + value + "\": unclosed IPv6 bracket — use the format host:port or a http(s):// URL");
            }
            host = raw.substring(1, closeBracket);
            String remainder = raw.substring(closeBracket + 1);
            if (remainder.startsWith(":")) {
                port = remainder.substring(1);
            }
        } else if (raw.contains(":")) {
            // host:port — split on the LAST colon (safe because we already stripped scheme)
            host = substringBeforeLast(raw, ":");
            port = substringAfterLast(raw, ":");
        } else {
            host = raw;
        }

        // Determine final port
        if (isBlank(port)) {
            if (schemeDefaultPort != null) {
                port = schemeDefaultPort;
            } else {
                List<String> errorMessages = List.of(
                    "proxy target \"" + value + "\" has no port — specify a port explicitly (e.g. --proxy-to " + host + ":8080) or use a http(s):// URL (e.g. --proxy-to https://" + host + ")"
                );
                printValidationError(errorMessages);
                throw new IllegalArgumentException(errorMessages.get(0));
            }
        }

        return new String[]{host, port};
    }

    // ---- Validation (preserves old error messages) ----

    private static List<String> validateArguments(String serverPortValue, String proxyRemotePortValue,
                                                  String proxyRemoteHostValue, String logLevelValue) {
        List<String> errorMessages = new ArrayList<>();

        if (isNotBlank(serverPortValue) && !serverPortValue.matches("^\\d+(,\\d+)*$")) {
            errorMessages.add("serverPort value \"" + serverPortValue + "\" is invalid, please specify a comma separated list of ports i.e. \"1080,1081,1082\"");
        }
        if (isNotBlank(proxyRemotePortValue) && !proxyRemotePortValue.matches("^\\d+$")) {
            errorMessages.add("proxyRemotePort value \"" + proxyRemotePortValue + "\" is invalid, please specify a port i.e. \"1080\"");
        }
        if (isNotBlank(proxyRemoteHostValue)) {
            String validIpAddressRegex = "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$";
            String validHostnameRegex = "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$";
            if (!(proxyRemoteHostValue.matches(validIpAddressRegex) || proxyRemoteHostValue.matches(validHostnameRegex))) {
                errorMessages.add("proxyRemoteHost value \"" + proxyRemoteHostValue + "\" is invalid, please specify a host name i.e. \"localhost\" or \"127.0.0.1\"");
            }
        }
        if (isNotBlank(logLevelValue) && !Arrays.asList("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "FINEST", "FINE", "WARNING", "SEVERE").contains(logLevelValue)) {
            errorMessages.add("logLevel value \"" + logLevelValue + "\" is invalid, please specify one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\" or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"");
        }

        return errorMessages;
    }

    private static void printValidationError(List<String> errorMessages) {
        int maxLengthMessage = 0;
        for (String errorMessage : errorMessages) {
            if (errorMessage.length() > maxLengthMessage) {
                maxLengthMessage = errorMessage.length();
            }
        }
        systemOut.println(NEW_LINE + "   " + com.google.common.base.Strings.padEnd("", maxLengthMessage, '='));
        for (String errorMessage : errorMessages) {
            systemOut.println("   " + errorMessage);
        }
        systemOut.println("   " + com.google.common.base.Strings.padEnd("", maxLengthMessage, '=') + NEW_LINE);
    }

    // ---- Arguments enum (preserved for backward compatibility and env/sysprop resolution) ----

    public enum Arguments {
        serverPort("SERVER_PORT"),
        proxyRemoteHost("PROXY_REMOTE_HOST"),
        proxyRemotePort("PROXY_REMOTE_PORT"),
        logLevel("LOG_LEVEL");

        private final String shortEnvironmentVariableName;

        Arguments(String shortEnvironmentVariableName) {
            this.shortEnvironmentVariableName = shortEnvironmentVariableName;
        }

        public String shortEnvironmentVariableName() {
            return shortEnvironmentVariableName;
        }

        public String longEnvironmentVariableName() {
            return "MOCKSERVER_" + shortEnvironmentVariableName;
        }

        public String systemPropertyName() {
            return "mockserver." + name();
        }
    }

}
