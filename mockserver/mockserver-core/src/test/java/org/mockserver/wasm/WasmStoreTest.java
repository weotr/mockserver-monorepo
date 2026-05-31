package org.mockserver.wasm;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link WasmStore} in-memory WASM module storage.
 */
public class WasmStoreTest {

    @Test
    public void shouldStoreAndRetrieveModule() {
        WasmStore store = new WasmStore();
        byte[] bytes = new byte[]{0x00, 0x61, 0x73, 0x6d};
        store.put("test", bytes);
        assertThat(store.get("test"), is(equalTo(bytes)));
    }

    @Test
    public void shouldReturnNullForUnknownModule() {
        WasmStore store = new WasmStore();
        assertThat(store.get("nonexistent"), is(nullValue()));
    }

    @Test
    public void shouldListAllModuleNames() {
        WasmStore store = new WasmStore();
        store.put("mod1", new byte[4]);
        store.put("mod2", new byte[4]);
        assertThat(store.listNames(), containsInAnyOrder("mod1", "mod2"));
    }

    @Test
    public void shouldReportContains() {
        WasmStore store = new WasmStore();
        store.put("present", new byte[4]);
        assertThat(store.contains("present"), is(true));
        assertThat(store.contains("absent"), is(false));
    }

    @Test
    public void shouldRemoveModule() {
        WasmStore store = new WasmStore();
        store.put("temp", new byte[4]);
        store.remove("temp");
        assertThat(store.get("temp"), is(nullValue()));
        assertThat(store.contains("temp"), is(false));
    }

    @Test
    public void shouldResetClearAllModules() {
        WasmStore store = new WasmStore();
        store.put("a", new byte[4]);
        store.put("b", new byte[4]);
        store.reset();
        assertThat(store.listNames(), is(empty()));
    }

    @Test
    public void shouldIgnoreNullName() {
        WasmStore store = new WasmStore();
        store.put(null, new byte[4]);
        assertThat(store.listNames(), is(empty()));
    }

    @Test
    public void shouldIgnoreNullBytes() {
        WasmStore store = new WasmStore();
        store.put("name", null);
        assertThat(store.contains("name"), is(false));
    }

    @Test
    public void shouldReturnNullForNullGet() {
        WasmStore store = new WasmStore();
        assertThat(store.get(null), is(nullValue()));
    }

    @Test
    public void shouldHandleNullContains() {
        WasmStore store = new WasmStore();
        assertThat(store.contains(null), is(false));
    }

    @Test
    public void shouldHandleNullRemove() {
        WasmStore store = new WasmStore();
        store.put("survive", new byte[4]);
        store.remove(null);
        assertThat(store.contains("survive"), is(true));
    }

    @Test
    public void shouldOverwriteExistingModule() {
        WasmStore store = new WasmStore();
        byte[] v1 = new byte[]{1};
        byte[] v2 = new byte[]{2};
        store.put("m", v1);
        store.put("m", v2);
        assertThat(store.get("m"), is(equalTo(v2)));
        assertThat(store.listNames(), hasSize(1));
    }
}
