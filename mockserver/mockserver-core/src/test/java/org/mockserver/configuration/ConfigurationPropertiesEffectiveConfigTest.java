package org.mockserver.configuration;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.ConfigurationProperties.SOURCE_DEFAULT;
import static org.mockserver.configuration.ConfigurationProperties.SOURCE_ENVIRONMENT_VARIABLE;
import static org.mockserver.configuration.ConfigurationProperties.SOURCE_PROPERTIES_FILE;
import static org.mockserver.configuration.ConfigurationProperties.SOURCE_SYSTEM_PROPERTY;
import static org.mockserver.configuration.ConfigurationProperties.resolveEffectiveSource;
import static org.mockserver.configuration.ConfigurationProperties.resolveExplicitValue;

/**
 * Verifies the effective-configuration diagnostic's pure source/value-resolution helpers
 * report the correct winning tier for every combination of system property, properties-file,
 * environment-variable, and default. Uses the pure overloads that take the raw tier values, so
 * it neither reads nor mutates global state and is safe in the parallel Surefire phase. The
 * end-to-end behaviour (real system properties, redaction, output format) is covered by
 * {@link ConfigurationPropertiesEffectiveConfigGlobalStateTest}.
 */
public class ConfigurationPropertiesEffectiveConfigTest {

    // --- source resolution: precedence order matches readPropertyHierarchically ---

    @Test
    public void systemPropertyWinsOverEverything() {
        assertThat(resolveEffectiveSource("sys", "file", "env"), is(SOURCE_SYSTEM_PROPERTY));
        assertThat(resolveExplicitValue("sys", "file", "env"), is("sys"));
    }

    @Test
    public void propertiesFileWinsWhenNoSystemProperty() {
        assertThat(resolveEffectiveSource(null, "file", "env"), is(SOURCE_PROPERTIES_FILE));
        assertThat(resolveExplicitValue(null, "file", "env"), is("file"));
    }

    @Test
    public void environmentVariableWinsWhenNoSystemPropertyOrFile() {
        assertThat(resolveEffectiveSource(null, null, "env"), is(SOURCE_ENVIRONMENT_VARIABLE));
        assertThat(resolveExplicitValue(null, null, "env"), is("env"));
    }

    @Test
    public void defaultWhenNothingSet() {
        assertThat(resolveEffectiveSource(null, null, null), is(SOURCE_DEFAULT));
        assertThat(resolveExplicitValue(null, null, null), is(nullValue()));
    }

    @Test
    public void blankTierValuesAreTreatedAsUnset() {
        // A blank system property must not win — the next non-blank tier supplies the value.
        assertThat(resolveEffectiveSource("", "file", "env"), is(SOURCE_PROPERTIES_FILE));
        assertThat(resolveEffectiveSource("  ", "  ", "env"), is(SOURCE_ENVIRONMENT_VARIABLE));
        assertThat(resolveExplicitValue("", "", "env"), is("env"));
        assertThat(resolveExplicitValue("   ", null, null), is(nullValue()));
    }

    // --- value resolution: surrounding quotes stripped exactly as the real reader does ---

    @Test
    public void surroundingQuotesAreStripped() {
        assertThat(resolveExplicitValue("\"quoted\"", null, null), is("quoted"));
        assertThat(resolveExplicitValue(null, "\"fromFile\"", null), is("fromFile"));
    }
}
