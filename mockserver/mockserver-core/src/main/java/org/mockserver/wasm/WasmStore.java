package org.mockserver.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for WASM modules, keyed by module name.
 * Modules are stored as raw byte arrays (the compiled WASM binary).
 */
public class WasmStore {

    private static final WasmStore INSTANCE = new WasmStore();

    private final ConcurrentHashMap<String, byte[]> modules = new ConcurrentHashMap<>();

    WasmStore() {
    }

    public static WasmStore getInstance() {
        return INSTANCE;
    }

    public void put(String name, byte[] wasmBytes) {
        if (name != null && wasmBytes != null) {
            modules.put(name, wasmBytes);
        }
    }

    public byte[] get(String name) {
        return name != null ? modules.get(name) : null;
    }

    public boolean contains(String name) {
        return name != null && modules.containsKey(name);
    }

    public void remove(String name) {
        if (name != null) {
            modules.remove(name);
        }
    }

    public List<String> listNames() {
        return new ArrayList<>(modules.keySet());
    }

    public void reset() {
        modules.clear();
    }
}
