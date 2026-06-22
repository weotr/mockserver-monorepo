package org.mockserver.validator.jsonschema;

import com.networknt.schema.Schema;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.lang.reflect.Field;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Verifies the content-keyed compiled-schema cache used by the OpenAPI request/response validation
 * hot path: identical schema content reuses a single compiled networknt {@link Schema} (so the
 * Schema/SchemaRegistry compilation happens once), while a cached validator still validates with
 * outcomes and messages identical to a freshly-constructed validator — and different schema content
 * does not share a compilation.
 *
 * <p>Each call to {@link JsonSchemaValidator#cachedJsonSchemaValidator} returns a fresh, thread-confined
 * wrapper carrying the caller's own logger; the <em>shared</em> thing is the compiled {@link Schema}
 * inside it (the expensive part). The tests read that private {@code validator} field reflectively to
 * assert the compilation is reused.
 */
public class JsonSchemaValidatorCacheTest {

    private final MockServerLogger logger = new MockServerLogger();

    private static final String SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"}},\"required\":[\"id\",\"name\"],\"additionalProperties\":false}";

    private static Schema compiledSchemaOf(JsonSchemaValidator validator) throws Exception {
        Field field = JsonSchemaValidator.class.getDeclaredField("validator");
        field.setAccessible(true);
        return (Schema) field.get(validator);
    }

    private static MockServerLogger loggerOf(JsonSchemaValidator validator) throws Exception {
        Field field = JsonSchemaValidator.class.getDeclaredField("mockServerLogger");
        field.setAccessible(true);
        return (MockServerLogger) field.get(validator);
    }

    @Test
    public void shouldReuseCompiledSchemaForIdenticalSchemaContent() throws Exception {
        // given / when - two lookups for the same schema content
        JsonSchemaValidator first = JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);
        JsonSchemaValidator second = JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);

        // then - distinct wrapper instances (thread-confined) ...
        assertThat(second, is(not(sameInstance(first))));
        // ... but the expensive compiled Schema is reused (compiled exactly once)
        Schema compiledFirst = compiledSchemaOf(first);
        Schema compiledSecond = compiledSchemaOf(second);
        assertThat(compiledFirst, is(notNullValue()));
        assertThat("compiled Schema must be reused across calls", compiledSecond, is(sameInstance(compiledFirst)));
    }

    @Test
    public void shouldReuseCompiledSchemaForEquivalentSchemaFromDifferentString() throws Exception {
        // given - the same schema content built as a fresh String (not the same object reference)
        String sameContentDifferentObject = new String(SCHEMA);

        // when
        JsonSchemaValidator first = JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);
        JsonSchemaValidator second = JsonSchemaValidator.cachedJsonSchemaValidator(logger, sameContentDifferentObject);

        // then - keyed by CONTENT (SHA-256), so distinct String objects with equal content share the compilation
        assertThat("content-keyed cache must share the compiled Schema", compiledSchemaOf(second), is(sameInstance(compiledSchemaOf(first))));
    }

    @Test
    public void shouldNotShareCompiledSchemaAcrossDifferentSchemaContent() throws Exception {
        // given - a structurally different schema
        String otherSchema = "{\"type\":\"string\"}";

        // when
        JsonSchemaValidator first = JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);
        JsonSchemaValidator other = JsonSchemaValidator.cachedJsonSchemaValidator(logger, otherSchema);

        // then - no cross-schema collision
        assertThat(compiledSchemaOf(other), is(not(sameInstance(compiledSchemaOf(first)))));
    }

    @Test
    public void cachedValidatorShouldUseCallersOwnLogger() throws Exception {
        // given - two callers with distinct logger instances against the same schema
        MockServerLogger loggerA = new MockServerLogger();
        MockServerLogger loggerB = new MockServerLogger();

        // when
        JsonSchemaValidator a = JsonSchemaValidator.cachedJsonSchemaValidator(loggerA, SCHEMA);
        JsonSchemaValidator b = JsonSchemaValidator.cachedJsonSchemaValidator(loggerB, SCHEMA);

        // then - each wrapper carries its OWN caller's logger (no first-caller capture), so error-log
        // routing is identical to constructing a validator per request
        assertThat(loggerOf(a), is(sameInstance(loggerA)));
        assertThat(loggerOf(b), is(sameInstance(loggerB)));
    }

    @Test
    public void cachedValidatorShouldAcceptValidPayload() {
        // given - a cached validator (second lookup reuses the compilation)
        JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);
        JsonSchemaValidator validator = JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);

        // when
        String result = validator.isValid("{\"id\": 1, \"name\": \"Fido\"}", false);

        // then - valid payload passes
        assertThat(result, is(""));
    }

    @Test
    public void cachedValidatorShouldRejectInvalidPayloadWithSameMessageAsFreshValidator() {
        // given - the cached validator and a freshly-built one for the same schema
        JsonSchemaValidator cached = JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);
        JsonSchemaValidator fresh = new JsonSchemaValidator(logger, SCHEMA);
        String invalidPayload = "{\"id\": \"not_a_number\", \"name\": \"Fido\"}";

        // when
        String cachedResult = cached.isValid(invalidPayload, false);
        String freshResult = fresh.isValid(invalidPayload, false);

        // then - the cached validator rejects it with the SAME message as a fresh validator (behaviour identity)
        assertThat(cachedResult, containsString("integer"));
        assertThat("cached validator must produce identical messages to a fresh one", cachedResult, is(freshResult));
    }

    @Test
    public void cachedValidatorShouldValidateCorrectlyUnderConcurrentUse() throws Exception {
        // given - hammer the SAME schema concurrently across many threads. This asserts validation
        // CORRECTNESS under concurrency (valid passes, invalid is rejected with the same message),
        // which must hold regardless of whether the shared compilation is hit or recompiled — so it
        // does not depend on the global cache-enabled flag and cannot flake on a parallel test run.
        JsonSchemaValidator.cachedJsonSchemaValidator(logger, SCHEMA);
        int threads = 16;
        int iterationsPerThread = 200;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < iterationsPerThread; i++) {
                    JsonSchemaValidator v = JsonSchemaValidator.cachedJsonSchemaValidator(new MockServerLogger(), SCHEMA);
                    if (!v.isValid("{\"id\": 1, \"name\": \"Fido\"}", false).isEmpty()) {
                        return false; // valid payload must always pass
                    }
                    if (!v.isValid("{\"id\": \"x\", \"name\": \"Fido\"}", false).contains("integer")) {
                        return false; // invalid payload must always be rejected with the same message
                    }
                }
                return true;
            }));
        }
        // when
        start.countDown();

        // then - every thread saw correct, consistent validation outcomes (no shared-state corruption)
        for (java.util.concurrent.Future<Boolean> f : futures) {
            assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS), is(true));
        }
        pool.shutdownNow();
    }
}
