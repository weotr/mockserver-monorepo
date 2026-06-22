package org.mockserver.wasm;

import com.dylibso.chicory.wasm.WasmModule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Verifies that {@link WasmRuntime} parses/validates a module's bytes at most once and reuses the
 * cached {@link WasmModule} across repeated match calls (and across separate {@link WasmRuntime}
 * instances built from the same bytes), while distinct bytes get distinct cached entries.
 * <p>
 * Parsing/validating the WASM binary is chicory's most expensive step and is pure given the bytes,
 * so the cache must turn the per-request re-parse into a one-time cost. The proof here is by object
 * identity: a re-parse would produce a <em>new</em> {@link WasmModule} object, so observing the
 * <em>same</em> object on the second call shows the cache served it rather than re-parsing.
 * <p>
 * Two real, prebuilt modules are used ({@code amount-over-1000.wasm} and {@code match-request.wasm})
 * so that "distinct modules cached separately" exercises genuinely parseable, byte-different inputs.
 */
public class WasmRuntimeModuleCacheTest {

    @Before
    @After
    public void clearCache() {
        // isolate from any module cached by other tests (and leave the cache clean afterwards)
        WasmRuntime.invalidateAll();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WasmRuntimeModuleCacheTest.class.getResourceAsStream(name)) {
            assertThat("test resource " + name + " must be on the classpath", in, notNullValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] amountModule() throws IOException {
        return resource("amount-over-1000.wasm");
    }

    private static byte[] requestModule() throws IOException {
        return resource("match-request.wasm");
    }

    @Test
    public void shouldParseSameModuleOnceAndReuseAcrossCalls() throws IOException {
        byte[] bytes = amountModule();
        WasmRuntime runtime = new WasmRuntime(bytes);

        // first call parses + caches; behaviour must still be correct
        assertThat(runtime.callMatch("{\"amount\": 5000}"), is(true));
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));
        WasmModule firstParsed = WasmRuntime.MODULE_CACHE.values().iterator().next();
        assertThat(firstParsed, notNullValue());

        // subsequent calls (same instance) must reuse the cached parsed module, not re-parse
        assertThat(runtime.callMatch("{\"amount\": 10}"), is(false));
        assertThat(runtime.callMatch("{\"amount\": 2500}"), is(true));
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));
        assertThat(WasmRuntime.MODULE_CACHE.values().iterator().next(), sameInstance(firstParsed));
    }

    @Test
    public void shouldReuseCachedModuleAcrossSeparateRuntimeInstances() throws IOException {
        new WasmRuntime(amountModule()).callMatch("{\"amount\": 5000}");
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));
        WasmModule firstParsed = WasmRuntime.MODULE_CACHE.values().iterator().next();

        // a brand-new runtime over identical (but freshly read) bytes must hit the cache (same parsed
        // object), since the cache is keyed by content hash, not by the WasmRuntime instance
        new WasmRuntime(amountModule()).callMatch("{\"amount\": 9000}");
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));
        assertThat(WasmRuntime.MODULE_CACHE.values().iterator().next(), sameInstance(firstParsed));
    }

    @Test
    public void shouldCacheDistinctModulesSeparately() throws IOException {
        new WasmRuntime(amountModule()).callMatch("{\"amount\": 5000}");
        new WasmRuntime(requestModule()).callMatch("{\"amount\": 5000}");

        // two distinct module binaries -> two distinct cached entries
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(2));
    }

    @Test
    public void shouldInvalidateCachedModuleByContent() throws IOException {
        byte[] bytes = amountModule();
        new WasmRuntime(bytes).callMatch("{\"amount\": 5000}");
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));

        WasmRuntime.invalidate(bytes);
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(0));

        // a subsequent call re-parses and re-caches, still producing correct results
        assertThat(new WasmRuntime(bytes).callMatch("{\"amount\": 5000}"), is(true));
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));
    }

    @Test
    public void shouldInvalidateAllCachedModules() throws IOException {
        new WasmRuntime(amountModule()).callMatch("{\"amount\": 5000}");
        new WasmRuntime(requestModule()).callMatch("{\"amount\": 5000}");
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(2));

        WasmRuntime.invalidateAll();
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(0));
    }

    @Test
    public void shouldEvictCachedModuleViaWasmStoreRemove() throws IOException {
        byte[] bytes = amountModule();
        WasmStore store = WasmStore.getInstance();
        try {
            store.put("cache-test-module", bytes);
            // populate the cache by running the module
            new WasmRuntime(store.get("cache-test-module")).callMatch("{\"amount\": 5000}");
            assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));

            // removing the module from the store must drop its parsed form from the cache
            store.remove("cache-test-module");
            assertThat(WasmRuntime.MODULE_CACHE.size(), is(0));
        } finally {
            store.remove("cache-test-module");
        }
    }

    @Test
    public void shouldClearCacheOnWasmStoreReset() throws IOException {
        WasmStore store = WasmStore.getInstance();
        store.put("reset-test-module", amountModule());
        new WasmRuntime(store.get("reset-test-module")).callMatch("{\"amount\": 5000}");
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(1));

        store.reset();
        assertThat(WasmRuntime.MODULE_CACHE.size(), is(0));
    }
}
