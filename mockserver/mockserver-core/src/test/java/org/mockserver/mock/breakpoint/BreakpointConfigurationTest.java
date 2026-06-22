package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests the configuration for breakpoint properties:
 * breakpointTimeoutMillis and breakpointMaxHeld (the global enable/disable
 * flags were removed in favour of the matcher-based registry).
 */
public class BreakpointConfigurationTest {

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
        ConfigurationProperties.breakpointTimeoutMillis(30_000);
        ConfigurationProperties.breakpointMaxHeld(50);
    }

    @After
    public void resetProperties() {
        ConfigurationProperties.breakpointTimeoutMillis(30_000);
        ConfigurationProperties.breakpointMaxHeld(50);
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    // --- ConfigurationProperties (static) defaults ---

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
        assertThat(config.breakpointTimeoutMillis(), is(30_000L));
        assertThat(config.breakpointMaxHeld(), is(50));
    }

    // --- Configuration (instance) overrides static ---

    @Test
    public void shouldUseInstanceOverrideWhenSet() {
        Configuration config = Configuration.configuration()
            .breakpointTimeoutMillis(5000L)
            .breakpointMaxHeld(10);

        assertThat(config.breakpointTimeoutMillis(), is(5000L));
        assertThat(config.breakpointMaxHeld(), is(10));
    }
}
