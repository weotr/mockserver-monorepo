package org.mockserver.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Module;
import com.dylibso.chicory.wasm.types.Value;

import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around a compiled chicory WASM instance.
 * <p>
 * Thread-safety: chicory {@link Instance} is NOT thread-safe, so a fresh
 * instance is created for each invocation. The cost is low because the
 * {@link Module} (parsed/validated bytecode) is reused.
 * <p>
 * The WASM module must export a function {@code match(i32 ptr, i32 len) -> i32}
 * that reads {@code len} bytes from linear memory starting at {@code ptr} and
 * returns non-zero for a match.
 * <p>
 * This class <strong>fails closed</strong>: any error returns {@code false}.
 */
public class WasmRuntime {

    private final byte[] wasmBytes;

    public WasmRuntime(byte[] wasmBytes) {
        this.wasmBytes = wasmBytes;
    }

    /**
     * Call the WASM module's {@code match()} function with the request body
     * written into linear memory at offset 0.
     *
     * @param requestBody the HTTP request body (may be null)
     * @return {@code true} if the module's {@code match} function returns non-zero
     */
    public boolean callMatch(String requestBody) {
        try {
            Module module = Module.builder(wasmBytes).build();
            Instance instance = module.instantiate();
            byte[] input = requestBody != null
                ? requestBody.getBytes(StandardCharsets.UTF_8)
                : new byte[0];

            // Write input into the WASM module's linear memory at offset 0
            instance.memory().write(0, input);

            // Call: i32 match(i32 ptr, i32 len)
            ExportFunction matchFn = instance.export("match");
            Value[] result = matchFn.apply(Value.i32(0), Value.i32(input.length));
            return result.length > 0 && result[0].asInt() != 0;
        } catch (Exception e) {
            // fail closed
            return false;
        }
    }
}
