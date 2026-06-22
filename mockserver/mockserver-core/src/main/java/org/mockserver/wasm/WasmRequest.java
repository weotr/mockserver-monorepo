package org.mockserver.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the parts of an HTTP request that a WASM matcher module can
 * inspect: the {@code method}, {@code path}, {@code headers}, and {@code body}.
 * <p>
 * This is the input to the <strong>richer WASM ABI</strong>. Modules that export
 * {@code match_request(i32 ptr, i32 len)} receive a JSON envelope built from this
 * object; modules that export only the legacy {@code match(i32 ptr, i32 len)}
 * receive just the {@link #getBody() body} bytes (back-compat).
 */
public class WasmRequest {

    private final String method;
    private final String path;
    private final Map<String, List<String>> headers;
    private final String body;

    public WasmRequest(String method, String path, Map<String, List<String>> headers, String body) {
        this.method = method == null ? "" : method;
        this.path = path == null ? "" : path;
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        this.body = body;
    }

    /**
     * Convenience factory for a body-only request (legacy behaviour). The method
     * and path are empty and there are no headers.
     */
    public static WasmRequest ofBody(String body) {
        return new WasmRequest("", "", new LinkedHashMap<>(), body);
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    /**
     * First value of the named header (case-insensitive), or {@code null} if absent.
     */
    public String getFirstHeader(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    public String getBody() {
        return body;
    }

    /**
     * Adds a header value, preserving insertion order and allowing multiple values
     * per name. Returns {@code this} for chaining.
     */
    public WasmRequest withHeader(String name, String value) {
        if (name != null) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return this;
    }
}
