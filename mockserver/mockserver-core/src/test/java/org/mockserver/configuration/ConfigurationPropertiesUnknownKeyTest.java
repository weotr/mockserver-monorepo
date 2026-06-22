package org.mockserver.configuration;

import org.junit.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static java.util.Collections.emptySet;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockserver.configuration.ConfigurationProperties.findUnknownConfigurationKeys;
import static org.mockserver.configuration.ConfigurationProperties.recognisedEnvironmentVariableKeys;
import static org.mockserver.configuration.ConfigurationProperties.recognisedSystemPropertyKeys;

/**
 * Verifies that unrecognised {@code mockserver.*} system properties, {@code MOCKSERVER_*}
 * environment variables, and {@code mockserver.*} properties-file keys are detected (so they
 * can be warned about), while recognised and unrelated keys are not flagged.
 *
 * <p>Uses the pure {@link ConfigurationProperties#findUnknownConfigurationKeys} helper with
 * explicit inputs, so it neither reads nor mutates global system-property / env state and is
 * safe in the parallel Surefire phase.</p>
 */
public class ConfigurationPropertiesUnknownKeyTest {

    private static Properties props(String... keys) {
        Properties properties = new Properties();
        for (String key : keys) {
            properties.setProperty(key, "value");
        }
        return properties;
    }

    // --- recognised-key enumeration (single source of truth, no drift) ---

    @Test
    public void recognisedKeysAreEnumeratedFromConstants() {
        Set<String> systemPropertyKeys = recognisedSystemPropertyKeys();
        Set<String> environmentVariableKeys = recognisedEnvironmentVariableKeys();

        // A representative selection of well-known properties must be recognised.
        assertThat(systemPropertyKeys, hasItem("mockserver.maxExpectations"));
        assertThat(systemPropertyKeys, hasItem("mockserver.metricsEnabled"));
        assertThat(systemPropertyKeys, hasItem("mockserver.logLevel"));

        assertThat(environmentVariableKeys, hasItem("MOCKSERVER_MAX_EXPECTATIONS"));
        assertThat(environmentVariableKeys, hasItem("MOCKSERVER_METRICS_ENABLED"));
        assertThat(environmentVariableKeys, hasItem("MOCKSERVER_LOG_LEVEL"));

        // Every recognised system-property key is in the mockserver. namespace.
        assertThat(systemPropertyKeys, everyItem(containsString("mockserver.")));
        // Every recognised environment-variable key is in the MOCKSERVER_ namespace.
        assertThat(environmentVariableKeys, everyItem(containsString("MOCKSERVER_")));
    }

    // --- (a) recognised key -> no warning ---

    @Test
    public void recognisedSystemPropertyProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.maxExpectations"), emptySet(), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void recognisedEnvironmentVariableProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("MOCKSERVER_MAX_EXPECTATIONS"), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void recognisedPropertiesFileKeyProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), emptySet(), props("mockserver.metricsEnabled", "mockserver.logLevel"));
        assertThat(unknown, is(empty()));
    }

    // --- (a2) launcher / CLI keys not declared as ConfigurationProperties constants -> no warning ---

    @Test
    public void serverPortKeysProduceNoWarning() {
        // mockserver.serverPort / MOCKSERVER_SERVER_PORT are the primary documented port knobs,
        // handled by the CLI launcher (Main.startServer), not by a MOCKSERVER_* constant.
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.serverPort"),
            Set.of("MOCKSERVER_SERVER_PORT"),
            props("mockserver.serverPort"));
        assertThat(unknown, is(empty()));
    }

    @Test
    public void launcherEnvironmentVariableProducesNoWarning() {
        // MOCKSERVER_LAUNCHER is exported by the binary-launcher scripts and explicitly excluded
        // from config loading by Main; it must not be flagged as an unknown configuration key.
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("MOCKSERVER_LAUNCHER"), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void otherLauncherAndPluginKeysProduceNoWarning() {
        // MOCKSERVER_JAVA_OPTS (launcher JVM opts), mockserver.mockServerPort (maven plugin),
        // and mockserver.launcherName (usage-text label) are all legitimate non-constant keys.
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.mockServerPort", "mockserver.launcherName"),
            Set.of("MOCKSERVER_JAVA_OPTS"),
            props());
        assertThat(unknown, is(empty()));
    }

    // --- (b) unrecognised key -> exactly one warning naming the key ---

    @Test
    public void unrecognisedSystemPropertyProducesExactlyOneWarningNamingTheKey() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.maxExpectatons"), emptySet(), props());
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("mockserver.maxExpectatons"));
        assertThat(unknown.get(0), containsString("system property"));
    }

    @Test
    public void unrecognisedEnvironmentVariableProducesExactlyOneWarningNamingTheKey() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("MOCKSERVER_METRICS_ENABLE"), props());
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("MOCKSERVER_METRICS_ENABLE"));
        assertThat(unknown.get(0), containsString("environment variable"));
    }

    @Test
    public void unrecognisedPropertiesFileKeyProducesExactlyOneWarningNamingTheKey() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), emptySet(), props("mockserver.maxExpectatons"));
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("mockserver.maxExpectatons"));
        assertThat(unknown.get(0), containsString("properties-file key"));
    }

    // --- (c) unrelated env var / system property -> no warning ---

    @Test
    public void unrelatedEnvironmentVariableProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("JAVA_HOME", "PATH", "HOME", "MOCK_SERVER_NOT_NAMESPACED"), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void unrelatedSystemPropertyProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("java.home", "user.dir", "mockserverish.typo"), emptySet(), props());
        assertThat(unknown, is(empty()));
    }

    // --- robustness ---

    @Test
    public void handlesNullPropertiesFile() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.typo"), emptySet(), null);
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("mockserver.typo"));
    }

    @Test
    public void reportsMultipleDistinctUnknownKeys() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.typoOne"),
            Set.of("MOCKSERVER_TYPO_TWO"),
            props("mockserver.typoThree"));
        assertThat(unknown, hasSize(3));
    }

    @Test
    public void doesNotDoubleReportSameKeyFromSystemPropertyAndPropertiesFile() {
        // A typo present in BOTH the system properties and the properties file is reported once.
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.typo"), emptySet(), props("mockserver.typo"));
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("system property"));
        assertThat(unknown.get(0), not(containsString("properties-file key")));
    }
}
