package org.mockserver.wasm;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link WasmRuntime} fail-closed behaviour.
 * <p>
 * Real WASM module execution is not tested here because building a valid
 * WASM binary in a unit test is complex. These tests verify that invalid
 * or null inputs return {@code false} (fail closed) rather than throwing.
 */
public class WasmRuntimeTest {

    @Test
    public void shouldReturnFalseForInvalidWasmBytes() {
        WasmRuntime runtime = new WasmRuntime(new byte[]{0x00, 0x01, 0x02, 0x03});
        assertThat(runtime.callMatch("hello"), is(false));
    }

    @Test
    public void shouldReturnFalseForEmptyWasmBytes() {
        WasmRuntime runtime = new WasmRuntime(new byte[0]);
        assertThat(runtime.callMatch("hello"), is(false));
    }

    @Test
    public void shouldReturnFalseForNullBody() {
        WasmRuntime runtime = new WasmRuntime(new byte[]{0x00, 0x61, 0x73, 0x6d});
        assertThat(runtime.callMatch(null), is(false));
    }

    @Test
    public void shouldReturnFalseForTruncatedWasmMagic() {
        // Valid WASM magic but no version or sections — should fail during parse
        WasmRuntime runtime = new WasmRuntime(new byte[]{0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        assertThat(runtime.callMatch("test"), is(false));
    }
}
