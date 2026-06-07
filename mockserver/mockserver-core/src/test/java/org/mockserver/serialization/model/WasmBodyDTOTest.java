package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.Body;
import org.mockserver.model.WasmBody;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link WasmBodyDTO} serialisation round-trip.
 */
public class WasmBodyDTOTest {

    @Test
    public void shouldBuildObjectFromDTO() {
        WasmBody original = new WasmBody("myModule");
        WasmBodyDTO dto = new WasmBodyDTO(original);
        WasmBody rebuilt = dto.buildObject();
        assertThat(rebuilt.getModuleName(), is("myModule"));
        assertThat(rebuilt.getType(), is(Body.Type.WASM));
    }

    @Test
    public void shouldReturnModuleName() {
        WasmBodyDTO dto = new WasmBodyDTO(new WasmBody("testMod"));
        assertThat(dto.getModuleName(), is("testMod"));
    }

    @Test
    public void shouldReturnWasmType() {
        WasmBodyDTO dto = new WasmBodyDTO(new WasmBody("mod"));
        assertThat(dto.getType(), is(Body.Type.WASM));
    }

    @Test
    public void shouldPreserveOptional() {
        WasmBody body = (WasmBody) new WasmBody("mod").withOptional(true);
        WasmBodyDTO dto = new WasmBodyDTO(body);
        assertThat(dto.getOptional(), is(true));
        WasmBody rebuilt = dto.buildObject();
        assertThat(rebuilt.getOptional(), is(true));
    }

    @Test
    public void shouldCreateDTOViaBodyDTOFactory() {
        WasmBody body = new WasmBody("mod");
        BodyDTO dto = BodyDTO.createDTO(body);
        assertThat(dto, is(instanceOf(WasmBodyDTO.class)));
        assertThat(((WasmBodyDTO) dto).getModuleName(), is("mod"));
    }

    @Test
    public void shouldConvertToStringViaBodyDTO() {
        WasmBodyDTO dto = new WasmBodyDTO(new WasmBody("myModule"));
        assertThat(BodyDTO.toString(dto), is("myModule"));
    }
}
