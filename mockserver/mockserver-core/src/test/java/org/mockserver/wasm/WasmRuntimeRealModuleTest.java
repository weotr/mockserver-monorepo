package org.mockserver.wasm;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * ABI-guard test that runs a <strong>real, compiled</strong> WASM module against
 * {@link WasmRuntime}, rather than the magic-number stubs in {@link WasmRuntimeTest}.
 * <p>
 * The module {@code amount-over-1000.wasm} is the prebuilt example shipped in
 * {@code examples/wasm/rust/} (Rust, {@code wasm32-unknown-unknown}). It exports
 * {@code match(i32 ptr, i32 len) -> i32} and matches when the request body contains
 * a JSON-style {@code "amount": <number>} greater than 1000.
 * <p>
 * If the MockServer WASM ABI ever changes (input written at offset 0, the exported
 * {@code match} signature, the exported {@code memory}), this test fails — which is
 * the point: it stops the documented example and the runtime from silently drifting
 * apart.
 */
public class WasmRuntimeRealModuleTest {

    private static byte[] amountOver1000Module() throws IOException {
        try (InputStream in = WasmRuntimeRealModuleTest.class.getResourceAsStream("amount-over-1000.wasm")) {
            assertThat("test resource amount-over-1000.wasm must be on the classpath", in, notNullValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Test
    public void shouldMatchWhenAmountExceedsThreshold() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch("{\"amount\": 5000}"), is(true));
    }

    @Test
    public void shouldNotMatchWhenAmountBelowThreshold() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch("{\"amount\": 10}"), is(false));
    }

    @Test
    public void shouldNotMatchAtThresholdBoundary() throws IOException {
        // rule is strictly greater-than 1000
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch("{\"amount\": 1000}"), is(false));
    }

    @Test
    public void shouldMatchJustAboveThresholdBoundary() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch("{\"amount\": 1001}"), is(true));
    }

    @Test
    public void shouldNotMatchWhenAmountFieldAbsent() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch("{\"status\": \"ok\"}"), is(false));
    }

    @Test
    public void shouldNotMatchEmptyBody() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch(""), is(false));
    }

    @Test
    public void shouldTolerateWhitespaceAroundColon() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());
        assertThat(runtime.callMatch("{ \"amount\" :   2500 }"), is(true));
    }
}
