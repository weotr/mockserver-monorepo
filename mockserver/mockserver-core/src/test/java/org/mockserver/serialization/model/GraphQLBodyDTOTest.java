package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.Body;
import org.mockserver.model.GraphQLBody;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class GraphQLBodyDTOTest {

    private static final String SDL = "type Query { hello: String }";

    @Test
    public void shouldCarrySchemaThroughDto() {
        GraphQLBody body = new GraphQLBody("{ hello }").withSchema(SDL);
        GraphQLBodyDTO dto = new GraphQLBodyDTO(body);

        assertThat(dto.getQuery(), is("{ hello }"));
        assertThat(dto.getSchema(), is(SDL));
        assertThat(dto.getType(), is(Body.Type.GRAPHQL));
    }

    @Test
    public void shouldRebuildObjectWithSchema() {
        GraphQLBody original = new GraphQLBody("{ hello }").withSchema(SDL);
        GraphQLBody rebuilt = new GraphQLBodyDTO(original).buildObject();

        assertThat(rebuilt.getSchema(), is(SDL));
        assertThat(rebuilt, is(original));
    }

    @Test
    public void shouldDefaultSchemaToNull() {
        GraphQLBody body = new GraphQLBody("{ hello }");
        assertThat(new GraphQLBodyDTO(body).getSchema(), nullValue());
    }

    @Test
    public void shouldSerializeSchemaField() throws Exception {
        GraphQLBody body = new GraphQLBody("{ hello }").withSchema(SDL);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new GraphQLBodyDTO(body));

        assertThat(json, containsString("\"schema\""));
        assertThat(json, containsString("type Query"));
    }

    @Test
    public void shouldRoundTripSchemaViaNestedGraphQLForm() throws Exception {
        String json = "{\"graphQL\":{\"query\":\"{ hello }\",\"schema\":\"" + SDL + "\"}}";
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        assertThat(deserialized, is(org.hamcrest.CoreMatchers.instanceOf(GraphQLBodyDTO.class)));
        assertThat(((GraphQLBodyDTO) deserialized).getSchema(), is(SDL));
    }

    @Test
    public void shouldRoundTripSchemaViaFlatForm() throws Exception {
        String json = "{\"type\":\"GRAPHQL\",\"query\":\"{ hello }\",\"schema\":\"" + SDL + "\"}";
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        assertThat(deserialized, is(org.hamcrest.CoreMatchers.instanceOf(GraphQLBodyDTO.class)));
        assertThat(((GraphQLBodyDTO) deserialized).getSchema(), is(SDL));
    }

    @Test
    public void shouldRoundTripSchemaViaFlatFormWhenSchemaKeyPrecedesTypeKey() throws Exception {
        // schema appears BEFORE type/query in the JSON document — capture must be order-independent
        String json = "{\"schema\":\"" + SDL + "\",\"query\":\"{ hello }\",\"type\":\"GRAPHQL\"}";
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        assertThat(deserialized, is(org.hamcrest.CoreMatchers.instanceOf(GraphQLBodyDTO.class)));
        assertThat(((GraphQLBodyDTO) deserialized).getSchema(), is(SDL));
    }
}
