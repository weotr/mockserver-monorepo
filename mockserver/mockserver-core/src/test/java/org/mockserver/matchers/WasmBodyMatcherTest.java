package org.mockserver.matchers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.wasm.WasmStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link WasmBodyMatcher}.
 * <p>
 * Real WASM module execution is not tested here — chicory integration
 * tests would require building a valid WASM binary. Instead, these tests
 * verify fail-closed behaviour, wasmEnabled gating, and basic wiring.
 */
public class WasmBodyMatcherTest {

    private boolean originalWasmEnabled;

    @Before
    public void saveConfig() {
        originalWasmEnabled = ConfigurationProperties.wasmEnabled();
    }

    @After
    public void resetStore() {
        WasmStore.getInstance().reset();
        ConfigurationProperties.wasmEnabled(originalWasmEnabled);
    }

    @Test
    public void shouldReturnFalseWhenWasmDisabled() {
        ConfigurationProperties.wasmEnabled(false);
        WasmStore.getInstance().put("someModule", new byte[]{0x00, 0x01, 0x02, 0x03});
        WasmBodyMatcher matcher = new WasmBodyMatcher("someModule");
        assertThat(matcher.matches(null, "some body"), is(false));
    }

    @Test
    public void shouldReturnFalseWhenModuleNotLoaded() {
        ConfigurationProperties.wasmEnabled(true);
        WasmBodyMatcher matcher = new WasmBodyMatcher("nonexistent");
        assertThat(matcher.matches(null, "some body"), is(false));
    }

    @Test
    public void shouldReturnFalseWhenModuleBytesAreInvalidWasm() {
        ConfigurationProperties.wasmEnabled(true);
        // Store invalid WASM bytes — callMatch should fail closed
        WasmStore.getInstance().put("invalid", new byte[]{0x00, 0x01, 0x02, 0x03});
        WasmBodyMatcher matcher = new WasmBodyMatcher("invalid");
        assertThat(matcher.matches(null, "hello"), is(false));
    }

    @Test
    public void shouldReturnFalseWhenModuleNameIsNull() {
        ConfigurationProperties.wasmEnabled(true);
        WasmBodyMatcher matcher = new WasmBodyMatcher(null);
        assertThat(matcher.matches(null, "body"), is(false));
    }

    @Test
    public void shouldReportBlankWhenModuleNameIsNull() {
        WasmBodyMatcher matcher = new WasmBodyMatcher(null);
        assertThat(matcher.isBlank(), is(true));
    }

    @Test
    public void shouldReportBlankWhenModuleNameIsEmpty() {
        WasmBodyMatcher matcher = new WasmBodyMatcher("");
        assertThat(matcher.isBlank(), is(true));
    }

    @Test
    public void shouldReportNotBlankWhenModuleNameIsSet() {
        WasmBodyMatcher matcher = new WasmBodyMatcher("myModule");
        assertThat(matcher.isBlank(), is(false));
    }
}
