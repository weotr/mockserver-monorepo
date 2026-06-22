package org.mockserver.graphql;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import graphql.introspection.IntrospectionResultToSchema;
import graphql.language.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.SelectionSetMatchType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Generates mock {@link Expectation}s from a GraphQL schema document (SDL text or an
 * introspection JSON result). This is the GraphQL analogue of the OpenAPI and WSDL
 * importers: it turns a schema into ready-to-serve mock expectations without any
 * hand-authored response JSON.
 *
 * <p>For each root operation type the schema defines (Query, Mutation, Subscription),
 * one expectation is generated that:
 * <ul>
 *   <li>matches any incoming GraphQL operation of that kind (POST to the configured
 *       GraphQL path), using {@link SelectionSetMatchType#AST_SUBSET} matching with an
 *       empty expected field set so every operation of the right kind matches;</li>
 *   <li>carries the supplied schema on its {@link GraphQLBody} and has no response body,
 *       so {@link GraphQLResponseSynthesizer} synthesizes a schema-valid
 *       {@code {"data": {...}}} response from the actual query at request time.</li>
 * </ul>
 *
 * <p>The schema is validated up front: an unparseable schema produces an
 * {@link IllegalArgumentException} so the import endpoint can report a 400, mirroring the
 * OpenAPI and WSDL importers.
 */
public class GraphQLExpectationGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_PATH = "/graphql";

    /**
     * Generate expectations from a GraphQL schema, matching GraphQL requests on the
     * default {@code /graphql} path.
     *
     * @param schemaDefinition SDL text or an introspection JSON result
     * @return one expectation per root operation type defined by the schema
     * @throws IllegalArgumentException if the schema is null, blank, or cannot be parsed
     */
    public List<Expectation> generate(String schemaDefinition) {
        return generate(schemaDefinition, DEFAULT_PATH);
    }

    /**
     * Generate expectations from a GraphQL schema, matching GraphQL requests on the
     * supplied path.
     *
     * @param schemaDefinition SDL text or an introspection JSON result
     * @param path             the request path to match (defaults to {@code /graphql} when blank)
     * @return one expectation per root operation type defined by the schema
     * @throws IllegalArgumentException if the schema is null, blank, or cannot be parsed
     */
    public List<Expectation> generate(String schemaDefinition, String path) {
        if (schemaDefinition == null || schemaDefinition.isBlank()) {
            throw new IllegalArgumentException("GraphQL schema definition was null or blank");
        }
        GraphQLSchema schema = buildSchema(schemaDefinition);
        String requestPath = (path == null || path.isBlank()) ? DEFAULT_PATH : path;

        List<Expectation> expectations = new ArrayList<>();
        GraphQLObjectType queryType = schema.getQueryType();
        if (queryType != null) {
            expectations.add(expectationFor("query", schemaDefinition, requestPath));
        }
        GraphQLObjectType mutationType = schema.getMutationType();
        if (mutationType != null) {
            expectations.add(expectationFor("mutation", schemaDefinition, requestPath));
        }
        GraphQLObjectType subscriptionType = schema.getSubscriptionType();
        if (subscriptionType != null) {
            expectations.add(expectationFor("subscription", schemaDefinition, requestPath));
        }

        if (expectations.isEmpty()) {
            throw new IllegalArgumentException("GraphQL schema defines no query, mutation or subscription root type to generate expectations from");
        }
        return expectations;
    }

    private Expectation expectationFor(String operationType, String schemaDefinition, String path) {
        // An empty selection set for the operation kind — AST_SUBSET matching with an
        // empty expected field set matches any operation of this kind.
        GraphQLBody graphQLBody = new GraphQLBody(operationType + " { }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withSchema(schemaDefinition);
        HttpRequest httpRequest = request()
            .withMethod("POST")
            .withPath(path)
            .withBody(graphQLBody);
        // No response body — GraphQLResponseSynthesizer fills it from the actual query.
        HttpResponse httpResponse = response();
        return new Expectation(httpRequest).thenRespond(httpResponse);
    }

    private static GraphQLSchema buildSchema(String schemaDefinition) {
        String trimmed = schemaDefinition.trim();
        try {
            TypeDefinitionRegistry registry;
            if (trimmed.startsWith("{")) {
                registry = fromIntrospectionJson(trimmed);
            } else {
                registry = new SchemaParser().parse(trimmed);
            }
            return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse GraphQL schema: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TypeDefinitionRegistry fromIntrospectionJson(String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode introspectionRoot;
            if (root.has("data") && root.get("data").has("__schema")) {
                introspectionRoot = root.get("data");
            } else if (root.has("__schema")) {
                introspectionRoot = root;
            } else {
                throw new IllegalArgumentException("introspection JSON did not contain a \"__schema\" object");
            }
            Map<String, Object> introspectionResult = OBJECT_MAPPER.convertValue(introspectionRoot, Map.class);
            Document document = new IntrospectionResultToSchema().createSchemaDefinition(introspectionResult);
            return new SchemaParser().buildRegistry(document);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse introspection JSON: " + e.getMessage(), e);
        }
    }
}
