package org.mockserver.serialization.serializers.schema;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.common.collect.ImmutableList;
import io.swagger.v3.oas.models.media.Schema;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("rawtypes")
public class AbstractSchemaSerializer<T extends Schema> extends StdSerializer<T> {
    private static final long serialVersionUID = 1L;

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.buildObjectMapperWithOnlyConfigurationDefaults();
    private static final List<String> fieldsToRemove = ImmutableList.of(
        "exampleSetFlag",
        "types"
    );

    public AbstractSchemaSerializer(Class<T> type) {
        super(type);
    }

    @Override
    public void serialize(T schema, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        ObjectNode jsonNodes = OBJECT_MAPPER.convertValue(schema, ObjectNode.class);
        recurse(jsonNodes, node -> {
            if (node instanceof ObjectNode) {
                ObjectNode objectNode = (ObjectNode) node;
                translateOas31Types(objectNode);
                normalizeExclusiveBounds(objectNode);
                objectNode.remove(fieldsToRemove);
            }
        });
        jgen.writeObject(jsonNodes);
    }

    /**
     * Translates an OpenAPI 3.1 {@code types} array into the Draft-07 / OAS-3.0 form this
     * serializer targets ({@code type} plus {@code nullable}), so the type information is never
     * silently dropped. The redundant {@code types} field is removed afterwards (see
     * {@link #fieldsToRemove}).
     * <ul>
     *   <li>Does nothing if {@code type} is already present.</li>
     *   <li>{@code ["string"]} -> {@code type: "string"}.</li>
     *   <li>A single non-null type plus {@code "null"} (e.g. {@code ["string","null"]}) ->
     *       {@code type: "string"} and {@code nullable: true}.</li>
     *   <li>Multiple non-null types (e.g. {@code ["string","integer"]}) -> {@code type} as a
     *       JSON-schema array of the non-null types, with {@code nullable: true} added when
     *       {@code "null"} was present.</li>
     * </ul>
     */
    private static void translateOas31Types(ObjectNode objectNode) {
        JsonNode typesNode = objectNode.get("types");
        if (typesNode == null || !typesNode.isArray() || typesNode.isEmpty() || objectNode.has("type")) {
            return;
        }
        ArrayNode nonNullTypes = OBJECT_MAPPER.createArrayNode();
        boolean hasNull = false;
        for (JsonNode typeNode : typesNode) {
            if (typeNode.isNull() || "null".equals(typeNode.asText())) {
                hasNull = true;
            } else {
                nonNullTypes.add(typeNode.asText());
            }
        }
        if (nonNullTypes.size() == 1) {
            objectNode.put("type", nonNullTypes.get(0).asText());
        } else if (nonNullTypes.size() > 1) {
            objectNode.set("type", nonNullTypes);
        }
        if (hasNull && !nonNullTypes.isEmpty() && !objectNode.has("nullable")) {
            objectNode.put("nullable", true);
        }
    }

    private static void normalizeExclusiveBounds(ObjectNode node) {
        normalizeExclusiveBound(node, "exclusiveMinimum", "minimum");
        normalizeExclusiveBound(node, "exclusiveMaximum", "maximum");
    }

    private static void normalizeExclusiveBound(ObjectNode node, String exclusiveField, String boundField) {
        JsonNode exclusiveNode = node.get(exclusiveField);
        if (exclusiveNode != null && exclusiveNode.isBoolean()) {
            if (exclusiveNode.booleanValue()) {
                JsonNode boundNode = node.get(boundField);
                if (boundNode != null && boundNode.isNumber()) {
                    node.set(exclusiveField, boundNode);
                    node.remove(boundField);
                } else {
                    node.remove(exclusiveField);
                }
            } else {
                node.remove(exclusiveField);
            }
        }
    }

    private void recurse(JsonNode node, Consumer<JsonNode> jsonNodeCallable) {
        jsonNodeCallable.accept(node);
        for (JsonNode jsonNode : node) {
            recurse(jsonNode, jsonNodeCallable);
        }
    }
}
