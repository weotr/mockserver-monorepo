package org.mockserver.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;

import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around a compiled chicory WASM instance.
 * <p>
 * Thread-safety: chicory {@link Instance} is NOT thread-safe, so the stored WASM
 * bytes are parsed into a {@link WasmModule} and a fresh {@link Instance} is
 * created for each invocation.
 * <p>
 * The WASM module must export a function {@code match(i32 ptr, i32 len) -> i32}
 * that reads {@code len} bytes from linear memory starting at {@code ptr} and
 * returns non-zero for a match.
 * <p>
 * This class <strong>fails closed</strong>: any error returns {@code false}.
 */
public class WasmRuntime {

    private final byte[] wasmBytes;
    private final int maxMemoryPages;

    /**
     * Create a runtime with the default memory page limit from
     * {@link org.mockserver.configuration.ConfigurationProperties#wasmMaxMemoryPages()}.
     */
    public WasmRuntime(byte[] wasmBytes) {
        this(wasmBytes, org.mockserver.configuration.ConfigurationProperties.wasmMaxMemoryPages());
    }

    /**
     * Create a runtime with an explicit memory page limit.
     *
     * @param wasmBytes      the compiled WASM binary
     * @param maxMemoryPages maximum number of WASM linear memory pages (each page is 64 KiB)
     */
    public WasmRuntime(byte[] wasmBytes, int maxMemoryPages) {
        this.wasmBytes = wasmBytes;
        this.maxMemoryPages = maxMemoryPages;
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
            WasmModule module = Parser.parse(wasmBytes);
            Instance.Builder builder = Instance.builder(module);

            // Cap the WASM module's linear memory at maxMemoryPages while preserving
            // the module's declared initial pages (needed for data segment initialization).
            if (module.memorySection().isPresent()
                && module.memorySection().get().memoryCount() > 0) {
                MemoryLimits declared = module.memorySection().get().getMemory(0).limits();
                int effectiveMax = Math.min(declared.maximumPages(), maxMemoryPages);
                int effectiveInit = Math.min(declared.initialPages(), effectiveMax);
                builder.withMemoryLimits(new MemoryLimits(effectiveInit, effectiveMax));
            }

            Instance instance = builder.build();
            byte[] input = requestBody != null
                ? requestBody.getBytes(StandardCharsets.UTF_8)
                : new byte[0];

            // Write input into the WASM module's linear memory at offset 0
            instance.memory().write(0, input);

            // Call: i32 match(i32 ptr, i32 len)
            ExportFunction matchFn = instance.export("match");
            long[] result = matchFn.apply(0L, input.length);
            return result.length > 0 && result[0] != 0;
        } catch (Exception e) {
            // fail closed
            return false;
        }
    }
}
