package org.mockserver.serialization.model;

import org.mockserver.model.Body;
import org.mockserver.model.WasmBody;

/**
 * DTO for serialising/deserialising {@link WasmBody} to/from JSON.
 */
public class WasmBodyDTO extends BodyDTO {

    private final String moduleName;

    public WasmBodyDTO(WasmBody wasmBody) {
        this(wasmBody, null);
    }

    public WasmBodyDTO(WasmBody wasmBody, Boolean not) {
        super(Body.Type.WASM, not);
        this.moduleName = wasmBody.getModuleName();
        withOptional(wasmBody.getOptional());
    }

    public String getModuleName() {
        return moduleName;
    }

    @Override
    public WasmBody buildObject() {
        return (WasmBody) new WasmBody(getModuleName()).withOptional(getOptional());
    }
}
