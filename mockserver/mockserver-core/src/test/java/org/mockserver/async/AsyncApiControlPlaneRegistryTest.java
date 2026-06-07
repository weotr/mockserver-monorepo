package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link AsyncApiControlPlaneRegistry} — the SPI holder in core
 * that delegates to the optional mockserver-async implementation.
 */
public class AsyncApiControlPlaneRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @After
    public void tearDown() {
        // Restore singleton to unregistered state for test isolation.
        // In production, register() is called once at startup and never cleared.
        AsyncApiControlPlaneRegistry.getInstance().register(null);
    }

    @Test
    public void shouldReturnNotAvailableWhenNoImplRegistered() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();
        assertThat(registry.isAvailable(), is(false));

        JsonNode loadResult = registry.load("{\"test\":true}");
        assertThat(loadResult.get("error").asText(), containsString("not available"));

        JsonNode statusResult = registry.status();
        assertThat(statusResult.get("error").asText(), containsString("not available"));

        String verifyResult = registry.verify("{\"channel\":\"test\"}");
        assertThat(verifyResult, is(notNullValue()));
        assertThat(verifyResult, containsString("not available"));
    }

    @Test
    public void shouldDelegateWhenImplRegistered() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();

        // Register a simple stub implementation
        registry.register(new AsyncApiControlPlane() {
            @Override
            public JsonNode load(String requestBody) {
                return MAPPER.createObjectNode().put("loaded", true);
            }

            @Override
            public JsonNode status() {
                return MAPPER.createObjectNode().put("status", "ok");
            }

            @Override
            public void reset() {
                // no-op
            }

            @Override
            public String verify(String verificationJson) {
                // Simulate: pass when channel is "ok", fail otherwise
                return verificationJson.contains("\"ok\"") ? null : "verification failed";
            }
        });

        assertThat(registry.isAvailable(), is(true));

        JsonNode loadResult = registry.load("{}");
        assertThat(loadResult.get("loaded").asBoolean(), is(true));

        JsonNode statusResult = registry.status();
        assertThat(statusResult.get("status").asText(), is("ok"));

        // Verify delegates correctly
        assertThat(registry.verify("{\"channel\":\"ok\"}"), is(nullValue()));
        assertThat(registry.verify("{\"channel\":\"fail\"}"), is("verification failed"));
    }

    @Test
    public void shouldResetSafelyWhenNoImpl() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();
        // Should not throw
        registry.reset();
    }
}
