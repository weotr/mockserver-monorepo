package org.mockserver.matchers;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.wasm.WasmRuntime;
import org.mockserver.wasm.WasmStore;

/**
 * Body matcher that delegates matching to a WASM module loaded in the {@link WasmStore}.
 * <p>
 * When WASM support is disabled ({@code wasmEnabled=false}), the matcher always
 * returns {@code false} (no match) — consistent with the fail-closed design.
 * <p>
 * Fails closed: returns {@code false} if the module is not loaded or throws.
 */
public class WasmBodyMatcher extends BodyMatcher<String> {

    private static final String[] EXCLUDED_FIELDS = new String[0];
    private final String moduleName;

    public WasmBodyMatcher(String moduleName) {
        this.moduleName = moduleName;
    }

    @Override
    public boolean matches(MatchDifference context, String actual) {
        if (!ConfigurationProperties.wasmEnabled()) {
            return false;
        }
        byte[] wasmBytes = WasmStore.getInstance().get(moduleName);
        if (wasmBytes == null) {
            return false;
        }
        boolean result = new WasmRuntime(wasmBytes).callMatch(actual);
        return not != result;
    }

    @Override
    public boolean isBlank() {
        return moduleName == null || moduleName.isEmpty();
    }

    @Override
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
