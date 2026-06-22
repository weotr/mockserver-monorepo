package org.mockserver.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.introspection.IntrospectionResultToSchema;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * Synthesizes schema-valid GraphQL responses for a matched query, given a registered
 * GraphQL schema. This is the GraphQL analogue of the OpenAPI {@code ExampleBuilder}: it
 * walks the requested selection set against the schema's type system and emits a JSON
 * {@code {"data": {...}}} envelope with deterministic, type-correct placeholder values —
 * no hand-authored response JSON required.
 *
 * <p>Supported schema input:
 * <ul>
 *   <li>SDL text, e.g. {@code "type Query { hello: String }"}</li>
 *   <li>An introspection JSON result — either the full {@code {"data": {"__schema": ...}}}
 *       envelope or the bare {@code {"__schema": ...}} object</li>
 * </ul>
 *
 * <p>Synthesis rules mirror GraphQL execution semantics:
 * <ul>
 *   <li>only requested fields (the selection set) appear in the response</li>
 *   <li>scalars produce deterministic placeholders (String, Int, Float, Boolean, ID and
 *       common custom scalars)</li>
 *   <li>enums produce their first declared value</li>
 *   <li>list types produce a single-element array</li>
 *   <li>object types recurse into their sub-selection</li>
 *   <li>non-null wrappers are unwrapped; nullable fields with no sub-selection on an object
 *       type yield {@code null} (only scalars/enums/lists are auto-populated as leaves)</li>
 *   <li>inline fragments and named fragment spreads are flattened into the selection</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe once constructed.
 */
public class GraphQLResponseSynthesizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_RECURSION_DEPTH = 25;

    private final GraphQLSchema schema;

    /**
     * Build a synthesizer from a GraphQL schema definition.
     *
     * @param schemaDefinition SDL text or an introspection JSON result
     * @throws GraphQLSchemaException if the schema cannot be parsed
     */
    public GraphQLResponseSynthesizer(String schemaDefinition) {
        this.schema = buildSchema(schemaDefinition);
    }

    private static GraphQLSchema buildSchema(String schemaDefinition) {
        if (schemaDefinition == null || schemaDefinition.isBlank()) {
            throw new GraphQLSchemaException("GraphQL schema definition was null or blank");
        }
        String trimmed = schemaDefinition.trim();
        try {
            TypeDefinitionRegistry registry;
            if (trimmed.startsWith("{")) {
                registry = fromIntrospectionJson(trimmed);
            } else {
                registry = new SchemaParser().parse(trimmed);
            }
            return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        } catch (GraphQLSchemaException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphQLSchemaException("Unable to parse GraphQL schema: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TypeDefinitionRegistry fromIntrospectionJson(String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            // Accept either the full {"data": {"__schema": ...}} envelope or a bare {"__schema": ...}
            JsonNode introspectionRoot = root;
            if (root.has("data") && root.get("data").has("__schema")) {
                introspectionRoot = root.get("data");
            } else if (root.has("__schema")) {
                introspectionRoot = root;
            } else {
                throw new GraphQLSchemaException("introspection JSON did not contain a \"__schema\" object");
            }
            Map<String, Object> introspectionResult =
                OBJECT_MAPPER.convertValue(introspectionRoot, Map.class);
            Document document = new IntrospectionResultToSchema().createSchemaDefinition(introspectionResult);
            return new SchemaParser().buildRegistry(document);
        } catch (GraphQLSchemaException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphQLSchemaException("Unable to parse introspection JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Synthesize a schema-valid response for the supplied GraphQL request body.
     *
     * @param requestBody the raw request body — either a JSON-wrapped GraphQL-over-HTTP body
     *                    ({@code {"query": "..."}}) or a raw GraphQL document
     * @return a JSON string of the form {@code {"data": {...}}}
     * @throws GraphQLSchemaException if the query cannot be parsed against the schema
     */
    public String synthesizeResponse(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            throw new GraphQLSchemaException("GraphQL request body was null or blank");
        }
        String query = extractQuery(requestBody);
        String operationName = extractOperationName(requestBody);

        Document document;
        try {
            document = Parser.parse(query);
        } catch (Exception e) {
            throw new GraphQLSchemaException("Unable to parse GraphQL query: " + e.getMessage(), e);
        }

        Map<String, FragmentDefinition> fragments = new HashMap<>();
        OperationDefinition operation = null;
        for (graphql.language.Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof FragmentDefinition) {
                FragmentDefinition fd = (FragmentDefinition) definition;
                fragments.put(fd.getName(), fd);
            } else if (definition instanceof OperationDefinition) {
                OperationDefinition od = (OperationDefinition) definition;
                if (operation == null
                    || (operationName != null && operationName.equals(od.getName()))) {
                    operation = od;
                }
            }
        }
        if (operation == null) {
            throw new GraphQLSchemaException("GraphQL document contained no operation to execute");
        }

        GraphQLObjectType rootType = rootTypeFor(operation.getOperation());
        if (rootType == null) {
            throw new GraphQLSchemaException(
                "schema does not define a root type for operation " + operation.getOperation());
        }

        ObjectNode data = synthesizeSelectionSet(rootType, operation.getSelectionSet(), fragments, 0);

        ObjectNode envelope = NODES.objectNode();
        envelope.set("data", data);
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new GraphQLSchemaException("Unable to serialize synthesized response: " + e.getMessage(), e);
        }
    }

    private GraphQLObjectType rootTypeFor(OperationDefinition.Operation operation) {
        switch (operation) {
            case MUTATION:
                return schema.getMutationType();
            case SUBSCRIPTION:
                return schema.getSubscriptionType();
            case QUERY:
            default:
                return schema.getQueryType();
        }
    }

    private ObjectNode synthesizeSelectionSet(GraphQLFieldsContainer container,
                                              SelectionSet selectionSet,
                                              Map<String, FragmentDefinition> fragments,
                                              int depth) {
        ObjectNode result = NODES.objectNode();
        if (selectionSet == null || depth > MAX_RECURSION_DEPTH) {
            return result;
        }
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field) {
                Field field = (Field) selection;
                String responseKey = field.getAlias() != null ? field.getAlias() : field.getName();
                if ("__typename".equals(field.getName())) {
                    result.put(responseKey, container.getName());
                    continue;
                }
                GraphQLFieldDefinition fieldDef = container.getFieldDefinition(field.getName());
                if (fieldDef == null) {
                    // requested field is not in the schema — emit null rather than fail
                    result.set(responseKey, NODES.nullNode());
                    continue;
                }
                result.set(responseKey,
                    synthesizeValue(fieldDef.getType(), field.getSelectionSet(), fragments, depth));
            } else if (selection instanceof InlineFragment) {
                InlineFragment inlineFragment = (InlineFragment) selection;
                // increment depth so the recursion guard also bounds nested/cyclic fragments
                ObjectNode nested = synthesizeSelectionSet(container,
                    inlineFragment.getSelectionSet(), fragments, depth + 1);
                mergeInto(result, nested);
            } else if (selection instanceof FragmentSpread) {
                FragmentSpread spread = (FragmentSpread) selection;
                FragmentDefinition fragment = fragments.get(spread.getName());
                if (fragment != null) {
                    // increment depth so a self-referential fragment spread cannot recurse forever
                    ObjectNode nested = synthesizeSelectionSet(container,
                        fragment.getSelectionSet(), fragments, depth + 1);
                    mergeInto(result, nested);
                }
            }
        }
        return result;
    }

    private JsonNode synthesizeValue(GraphQLOutputType type,
                                     SelectionSet subSelection,
                                     Map<String, FragmentDefinition> fragments,
                                     int depth) {
        GraphQLType unwrapped = type;
        if (unwrapped instanceof GraphQLNonNull) {
            unwrapped = ((GraphQLNonNull) unwrapped).getWrappedType();
        }
        if (unwrapped instanceof GraphQLList) {
            GraphQLType itemType = ((GraphQLList) unwrapped).getWrappedType();
            ArrayNode array = NODES.arrayNode();
            array.add(synthesizeValue((GraphQLOutputType) itemType, subSelection, fragments, depth));
            return array;
        }
        if (unwrapped instanceof GraphQLScalarType) {
            return scalarValue(((GraphQLScalarType) unwrapped).getName());
        }
        if (unwrapped instanceof GraphQLEnumType) {
            GraphQLEnumType enumType = (GraphQLEnumType) unwrapped;
            if (!enumType.getValues().isEmpty()) {
                return NODES.textNode(enumType.getValues().get(0).getName());
            }
            return NODES.nullNode();
        }
        if (unwrapped instanceof GraphQLFieldsContainer) {
            // object / interface — recurse into the requested sub-selection
            if (subSelection == null) {
                return NODES.nullNode();
            }
            return synthesizeSelectionSet((GraphQLFieldsContainer) unwrapped, subSelection, fragments, depth + 1);
        }
        // unions and anything else without a concrete shape we can synthesize
        return NODES.nullNode();
    }

    private JsonNode scalarValue(String scalarName) {
        switch (scalarName) {
            case "Int":
                return NODES.numberNode(0);
            case "Float":
                return NODES.numberNode(0.0);
            case "Boolean":
                return NODES.booleanNode(true);
            case "ID":
                return NODES.textNode("1");
            case "String":
                return NODES.textNode("string");
            default:
                // common custom scalars — best-effort deterministic placeholders
                String lower = scalarName.toLowerCase();
                if (lower.contains("datetime") || lower.contains("timestamp")) {
                    return NODES.textNode("2020-01-01T00:00:00Z");
                }
                if (lower.contains("date")) {
                    return NODES.textNode("2020-01-01");
                }
                if (lower.contains("time")) {
                    return NODES.textNode("00:00:00");
                }
                if (lower.contains("json")) {
                    return NODES.objectNode();
                }
                if (lower.contains("long") || lower.contains("bigint") || lower.contains("int")) {
                    return NODES.numberNode(0);
                }
                if (lower.contains("decimal") || lower.contains("float") || lower.contains("double")) {
                    return NODES.numberNode(0.0);
                }
                if (lower.contains("boolean") || lower.contains("bool")) {
                    return NODES.booleanNode(true);
                }
                // unknown custom scalar — string is the safest default
                return NODES.textNode("string");
        }
    }

    private static void mergeInto(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    static String extractQuery(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"query\"")) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                JsonNode queryNode = node.get("query");
                if (queryNode != null && queryNode.isTextual()) {
                    return queryNode.asText();
                }
            } catch (Exception ignored) {
                // fall through to raw handling
            }
        }
        return trimmed;
    }

    static String extractOperationName(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                JsonNode opNode = node.get("operationName");
                if (opNode != null && opNode.isTextual()) {
                    return opNode.asText();
                }
            } catch (Exception ignored) {
                // no operationName
            }
        }
        return null;
    }
}
