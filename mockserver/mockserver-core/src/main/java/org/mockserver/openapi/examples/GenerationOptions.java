package org.mockserver.openapi.examples;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-generation-run options controlling how {@link ExampleBuilder} produces example values
 * when the {@code generateRealisticExampleValues} configuration flag is enabled.
 *
 * <p>Two knobs are supported, both optional and backward-compatible:</p>
 * <ul>
 *   <li><b>seed</b> — a caller-chosen seed threaded into {@link SampleDataGenerator} so a given
 *       spec produces identical realistic output across runs. When {@code null} the generator
 *       falls back to its historic fixed seed (42), so existing behaviour is unchanged.</li>
 *   <li><b>fieldOverrides</b> — a map of property name to fixed value. Whenever a schema property
 *       whose name matches a key is generated, the fixed value is emitted instead of a generated
 *       one. For example {@code {"userId": "system-user-123"}} pins every {@code userId} field.</li>
 * </ul>
 *
 * <p><b>Match semantics (v1):</b> overrides match by <em>leaf property name</em> — the map key is
 * compared against the property/JSON field name being generated, at any nesting depth. There is no
 * JSONPath or dotted-path matching in this version; a key of {@code userId} pins every property
 * named {@code userId} regardless of where it appears in the schema. This keeps the contract simple
 * and predictable; richer path matching can layer on later without breaking this behaviour.</p>
 *
 * <p>Overrides only take effect when there is no explicit {@code example} already declared on the
 * schema (an author-declared example always wins), and they apply whether or not realistic value
 * generation is enabled.</p>
 */
public class GenerationOptions {

    /**
     * Reserved key under which a caller may embed these options inside the
     * {@code operationsAndResponses} map of an OpenAPI import request. The value is a map with
     * optional {@code "seed"} (number) and {@code "fieldOverrides"} (object) entries. The key is
     * deliberately namespaced so it can never collide with a real {@code operationId}.
     */
    public static final String OPERATIONS_KEY = "__generationOptions__";

    private final Long seed;
    private final Map<String, Object> fieldOverrides;

    public GenerationOptions(Long seed, Map<String, Object> fieldOverrides) {
        this.seed = seed;
        this.fieldOverrides = fieldOverrides == null || fieldOverrides.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fieldOverrides));
    }

    /**
     * @return the caller-chosen seed, or {@code null} to use the historic fixed seed (42)
     */
    public Long getSeed() {
        return seed;
    }

    public Map<String, Object> getFieldOverrides() {
        return fieldOverrides;
    }

    public boolean hasFieldOverrides() {
        return !fieldOverrides.isEmpty();
    }

    /**
     * @param propertyName the leaf property/field name currently being generated
     * @return {@code true} if a fixed override value is registered for that exact property name
     */
    public boolean hasOverrideFor(String propertyName) {
        return propertyName != null && fieldOverrides.containsKey(propertyName);
    }

    public Object getOverride(String propertyName) {
        return propertyName == null ? null : fieldOverrides.get(propertyName);
    }

    /**
     * Builds a {@link GenerationOptions} from the reserved {@link #OPERATIONS_KEY} entry of an
     * OpenAPI import {@code operationsAndResponses} map, or {@code null} if none is present.
     */
    @SuppressWarnings("unchecked")
    public static GenerationOptions fromOperationsMap(Map<String, Object> operationsAndResponses) {
        if (operationsAndResponses == null) {
            return null;
        }
        Object raw = operationsAndResponses.get(OPERATIONS_KEY);
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> options = (Map<String, Object>) raw;
        Long seed = null;
        Object seedValue = options.get("seed");
        if (seedValue instanceof Number number) {
            seed = number.longValue();
        } else if (seedValue instanceof String string && !string.isBlank()) {
            try {
                seed = Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                // leave seed null — fall back to default
            }
        }
        Map<String, Object> fieldOverrides = null;
        Object overridesValue = options.get("fieldOverrides");
        if (overridesValue instanceof Map) {
            fieldOverrides = (Map<String, Object>) overridesValue;
        }
        if (seed == null && (fieldOverrides == null || fieldOverrides.isEmpty())) {
            return null;
        }
        return new GenerationOptions(seed, fieldOverrides);
    }
}
