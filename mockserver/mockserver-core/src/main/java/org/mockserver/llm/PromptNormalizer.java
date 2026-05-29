package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.NormalizationOptions;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Deterministic normalisation of LLM prompt text prior to conversation-predicate
 * matching. Pure and side-effect-free: the same input plus options always yields
 * the same output, so normalised matching never makes a test flaky.
 * <p>
 * When the input parses as JSON, structural operations (key sorting, field
 * dropping) are applied to the parsed tree; otherwise the input is treated as
 * plain text. The text path never throws — any parse failure falls back to
 * text-only normalisation.
 *
 * @see NormalizationOptions
 */
public final class PromptNormalizer {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    // Built-in volatile-value patterns, stripped when dropBuiltInVolatileFields is set.
    // ISO-8601 timestamps (date, optional time/zone), UUIDs, and prefix_… ids.
    // The id pattern requires a suffix of at least 6 alphanumerics so that short
    // English words after an underscore (e.g. "call_me") are not mistaken for ids;
    // real provider ids (OpenAI, Anthropic, …) are far longer than this floor.
    private static final Pattern ISO_8601 = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}:?\\d{2})?)?");
    private static final Pattern UUID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern PREFIXED_ID = Pattern.compile(
        "\\b(?:req|msg|call|tool|toolu|chatcmpl|run|resp|asst|thread)_[A-Za-z0-9]{6,}\\b");

    private static final String VOLATILE_PLACEHOLDER = "";

    private PromptNormalizer() {
    }

    /**
     * Normalise {@code text} according to {@code options}. A null {@code options}
     * (or null {@code text}) returns the input unchanged.
     *
     * @param text    the prompt text to normalise (may be null)
     * @param options the normalisation options (may be null)
     * @return the normalised text, never throwing
     */
    public static String normalize(String text, NormalizationOptions options) {
        if (text == null || options == null) {
            return text;
        }
        // Resolve nullable flags to their defaults so the behaviour is identical
        // regardless of how the options were populated (REST/Jackson vs MCP).
        boolean lowercase = orDefault(options.getLowercase(), NormalizationOptions.DEFAULT_LOWERCASE);
        boolean sortJsonKeys = orDefault(options.getSortJsonKeys(), NormalizationOptions.DEFAULT_SORT_JSON_KEYS);
        boolean dropBuiltIn = orDefault(options.getDropBuiltInVolatileFields(), NormalizationOptions.DEFAULT_DROP_BUILT_IN_VOLATILE_FIELDS);
        boolean collapseWhitespace = orDefault(options.getCollapseWhitespace(), NormalizationOptions.DEFAULT_COLLAPSE_WHITESPACE);
        List<String> dropFields = options.getDropVolatileFields();

        String result = text;

        // Lowercase first so JSON keys are lowercased before they are sorted —
        // sorting then lowercasing would reorder keys on a second pass and break
        // idempotency. Operating on the running result also means a JSON parse
        // failure below preserves work already done rather than discarding it.
        if (lowercase) {
            result = result.toLowerCase();
        }

        // Structural JSON operations, while the document is still parseable.
        boolean needsJson = sortJsonKeys || (dropFields != null && !dropFields.isEmpty());
        if (needsJson) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(result);
                if (root != null && (root.isObject() || root.isArray())) {
                    result = OBJECT_MAPPER.writeValueAsString(transform(root, sortJsonKeys, dropFields));
                }
            } catch (Exception e) {
                // Not JSON (or malformed) — keep the running result (text-only path).
            }
        }

        if (dropBuiltIn) {
            result = ISO_8601.matcher(result).replaceAll(VOLATILE_PLACEHOLDER);
            result = UUID.matcher(result).replaceAll(VOLATILE_PLACEHOLDER);
            result = PREFIXED_ID.matcher(result).replaceAll(VOLATILE_PLACEHOLDER);
        }
        if (collapseWhitespace) {
            result = result.replaceAll("\\s+", " ").trim();
        }
        return result;
    }

    private static boolean orDefault(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Recursively rebuild a JSON tree with object keys sorted and dropped
     * volatile fields removed (when the corresponding options are set).
     */
    private static JsonNode transform(JsonNode node, boolean sortJsonKeys, List<String> dropFields) {
        if (node.isObject()) {
            ObjectNode source = (ObjectNode) node;
            // TreeMap gives deterministic key ordering when sortJsonKeys is set;
            // otherwise preserve insertion order via a plain list of names.
            Iterable<String> fieldNames;
            if (sortJsonKeys) {
                Map<String, JsonNode> sorted = new TreeMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = source.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    sorted.put(e.getKey(), e.getValue());
                }
                fieldNames = sorted.keySet();
            } else {
                List<String> names = new ArrayList<>();
                source.fieldNames().forEachRemaining(names::add);
                fieldNames = names;
            }
            ObjectNode out = OBJECT_MAPPER.createObjectNode();
            for (String name : fieldNames) {
                if (dropFields != null && dropFields.contains(name)) {
                    continue;
                }
                out.set(name, transform(source.get(name), sortJsonKeys, dropFields));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = OBJECT_MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(transform(child, sortJsonKeys, dropFields));
            }
            return out;
        }
        return node;
    }
}
