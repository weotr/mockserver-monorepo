package org.mockserver.templates.engine.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * YAML helpers for templates: convert YAML to JSON and read a field out of a
 * YAML document. Backed by Jackson's YAML dataformat (already on the classpath
 * via {@link org.mockserver.serialization.YamlToJsonConverter}).
 */
public class YamlTemplateHelper {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Converts a YAML document to its JSON representation. Returns an empty
     * string for {@code null} or empty input, and the original input if it
     * cannot be parsed as YAML.
     */
    public String toJson(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return "";
        }
        try {
            JsonNode node = yamlMapper.readTree(yaml);
            return jsonMapper.writeValueAsString(node);
        } catch (Exception exception) {
            return yaml;
        }
    }

    /**
     * Reads a top-level field from a YAML document and returns its value as a
     * string (scalars are returned verbatim, objects/arrays as JSON). Returns
     * an empty string when the field is absent or the input cannot be parsed.
     */
    public String parse(String yaml, String fieldName) {
        if (yaml == null || yaml.isEmpty() || fieldName == null) {
            return "";
        }
        try {
            JsonNode node = yamlMapper.readTree(yaml);
            JsonNode fieldNode = node != null ? node.get(fieldName) : null;
            if (fieldNode == null) {
                return "";
            }
            return fieldNode.isValueNode() ? fieldNode.asText() : jsonMapper.writeValueAsString(fieldNode);
        } catch (Exception exception) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "YamlTemplateHelper";
    }
}
