package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests the 4-form configuration for breakpoint properties:
 * ConfigurationProperties (static/system property) and Configuration (instance).
 */
public class BreakpointConfigurationTest {

    @After
    public void resetProperties() {
        // reset to defaults
        ConfigurationProperties.breakpointEnabled(false);
        ConfigurationProperties.breakpointResponseEnabled(false);
        ConfigurationProperties.breakpointTimeoutMillis(30_000);
        ConfigurationProperties.breakpointMaxHeld(50);
    }

    // --- ConfigurationProperties (static) defaults ---

    @Test
    public void shouldDefaultBreakpointEnabledToFalse() {
        assertThat(ConfigurationProperties.breakpointEnabled(), is(false));
    }

    @Test
    public void shouldDefaultBreakpointTimeoutMillisTo30000() {
        assertThat(ConfigurationProperties.breakpointTimeoutMillis(), is(30_000L));
    }

    @Test
    public void shouldDefaultBreakpointMaxHeldTo50() {
        assertThat(ConfigurationProperties.breakpointMaxHeld(), is(50));
    }

    // --- ConfigurationProperties (static) set/get ---

    @Test
    public void shouldSetAndGetBreakpointEnabled() {
        ConfigurationProperties.breakpointEnabled(true);
        assertThat(ConfigurationProperties.breakpointEnabled(), is(true));
    }

    @Test
    public void shouldSetAndGetBreakpointTimeoutMillis() {
        ConfigurationProperties.breakpointTimeoutMillis(5000);
        assertThat(ConfigurationProperties.breakpointTimeoutMillis(), is(5000L));
    }

    @Test
    public void shouldSetAndGetBreakpointMaxHeld() {
        ConfigurationProperties.breakpointMaxHeld(10);
        assertThat(ConfigurationProperties.breakpointMaxHeld(), is(10));
    }

    // --- Configuration (instance) delegates to static when null ---

    @Test
    public void shouldDelegateToStaticWhenInstanceFieldsNull() {
        Configuration config = Configuration.configuration();
        assertThat(config.breakpointEnabled(), is(false));
        assertThat(config.breakpointTimeoutMillis(), is(30_000L));
        assertThat(config.breakpointMaxHeld(), is(50));
    }

    // --- Configuration (instance) overrides static ---

    @Test
    public void shouldUseInstanceOverrideWhenSet() {
        Configuration config = Configuration.configuration()
            .breakpointEnabled(true)
            .breakpointTimeoutMillis(5000L)
            .breakpointMaxHeld(10);

        assertThat(config.breakpointEnabled(), is(true));
        assertThat(config.breakpointTimeoutMillis(), is(5000L));
        assertThat(config.breakpointMaxHeld(), is(10));
    }

    // --- breakpointResponseEnabled ---

    @Test
    public void shouldDefaultBreakpointResponseEnabledToFalse() {
        assertThat(ConfigurationProperties.breakpointResponseEnabled(), is(false));
    }

    @Test
    public void shouldSetAndGetBreakpointResponseEnabled() {
        ConfigurationProperties.breakpointResponseEnabled(true);
        assertThat(ConfigurationProperties.breakpointResponseEnabled(), is(true));
    }

    @Test
    public void shouldDelegateResponseEnabledToStaticWhenNull() {
        Configuration config = Configuration.configuration();
        assertThat(config.breakpointResponseEnabled(), is(false));
    }

    @Test
    public void shouldUseInstanceOverrideForResponseEnabled() {
        Configuration config = Configuration.configuration()
            .breakpointResponseEnabled(true);
        assertThat(config.breakpointResponseEnabled(), is(true));
    }

    // --- default-off path ---

    @Test
    public void defaultOffShouldNotPauseAnything() {
        // when breakpoints are disabled (the default), the registry should
        // never be consulted, so the forward path is unaffected
        Configuration config = Configuration.configuration();
        assertThat("default is off", config.breakpointEnabled(), is(false));
        assertThat("response default is off", config.breakpointResponseEnabled(), is(false));
    }
}
