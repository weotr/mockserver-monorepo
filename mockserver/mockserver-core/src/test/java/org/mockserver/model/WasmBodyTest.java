package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.WasmBody.wasmBody;

/**
 * Tests for {@link WasmBody} model class.
 */
public class WasmBodyTest {

    @Test
    public void shouldReturnModuleName() {
        WasmBody body = new WasmBody("myModule");
        assertThat(body.getModuleName(), is("myModule"));
        assertThat(body.getValue(), is("myModule"));
    }

    @Test
    public void shouldReturnWasmType() {
        WasmBody body = new WasmBody("myModule");
        assertThat(body.getType(), is(Body.Type.WASM));
    }

    @Test
    public void shouldCreateViaStaticFactory() {
        WasmBody body = wasmBody("testMod");
        assertThat(body.getModuleName(), is("testMod"));
        assertThat(body.getType(), is(Body.Type.WASM));
    }

    @Test
    public void shouldBeEqualWhenSameModuleName() {
        assertThat(new WasmBody("mod"), is(equalTo(new WasmBody("mod"))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentModuleName() {
        assertThat(new WasmBody("mod1"), is(not(equalTo(new WasmBody("mod2")))));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        assertThat(new WasmBody("mod").hashCode(), is(new WasmBody("mod").hashCode()));
    }
}
