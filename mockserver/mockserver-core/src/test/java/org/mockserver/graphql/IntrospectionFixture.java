package org.mockserver.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.Map;

/**
 * Test helper: runs the standard GraphQL introspection query against an SDL schema and
 * returns the resulting introspection JSON, so synthesis from real introspection results
 * can be exercised without checking in a large hand-written fixture.
 */
final class IntrospectionFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IntrospectionFixture() {
    }

    static String introspectionJsonFor(String sdl) throws Exception {
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
        GraphQLSchema schema = new SchemaGenerator()
            .makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
        GraphQL graphQL = GraphQL.newGraphQL(schema).build();
        ExecutionResult result = graphQL.execute(IntrospectionQuery.INTROSPECTION_QUERY);
        Map<String, Object> spec = result.toSpecification();
        return MAPPER.writeValueAsString(spec);
    }
}
