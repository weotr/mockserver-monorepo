package org.mockserver.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavioural tests for schema-driven GraphQL response synthesis. Each test registers an
 * SDL (or introspection) schema and a query, then asserts that the synthesized response is
 * schema-valid (right keys, right JSON types, only requested fields) — with no
 * hand-authored response JSON anywhere.
 */
public class GraphQLResponseSynthesizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode synthesize(String schema, String query) throws Exception {
        String response = new GraphQLResponseSynthesizer(schema).synthesizeResponse(query);
        return MAPPER.readTree(response);
    }

    @Test
    public void shouldSynthesizeScalarTypes() throws Exception {
        String schema = "type Query { " +
            "  s: String " +
            "  i: Int " +
            "  f: Float " +
            "  b: Boolean " +
            "  id: ID " +
            "}";
        JsonNode data = synthesize(schema, "{ s i f b id }").get("data");

        assertTrue("String -> textual", data.get("s").isTextual());
        assertTrue("Int -> integral number", data.get("i").isIntegralNumber());
        assertTrue("Float -> floating number", data.get("f").isFloatingPointNumber());
        assertTrue("Boolean -> boolean", data.get("b").isBoolean());
        assertTrue("ID -> textual", data.get("id").isTextual());
    }

    @Test
    public void shouldOnlyIncludeRequestedFields(/* subset of the type */) throws Exception {
        String schema = "type Query { user: User } " +
            "type User { id: ID name: String email: String age: Int }";
        // selection set is a strict subset of User's fields
        JsonNode data = synthesize(schema, "{ user { name email } }").get("data");
        JsonNode user = data.get("user");

        assertThat(user.size(), is(2));
        assertTrue(user.has("name"));
        assertTrue(user.has("email"));
        assertFalse("unrequested field id must be absent", user.has("id"));
        assertFalse("unrequested field age must be absent", user.has("age"));
    }

    @Test
    public void shouldSynthesizeNestedObjects() throws Exception {
        String schema = "type Query { user: User } " +
            "type User { name: String address: Address } " +
            "type Address { city: String zip: String }";
        JsonNode data = synthesize(schema, "{ user { name address { city zip } } }").get("data");

        JsonNode address = data.get("user").get("address");
        assertTrue(address.isObject());
        assertTrue(address.get("city").isTextual());
        assertTrue(address.get("zip").isTextual());
    }

    @Test
    public void shouldSynthesizeListsAsArrays() throws Exception {
        String schema = "type Query { users: [User] } " +
            "type User { id: ID name: String }";
        JsonNode data = synthesize(schema, "{ users { id name } }").get("data");

        JsonNode users = data.get("users");
        assertTrue("list field -> array", users.isArray());
        assertThat(users.size(), is(1));
        assertTrue(users.get(0).get("name").isTextual());
    }

    @Test
    public void shouldSynthesizeScalarListAsArray() throws Exception {
        String schema = "type Query { tags: [String] }";
        JsonNode data = synthesize(schema, "{ tags }").get("data");

        JsonNode tags = data.get("tags");
        assertTrue(tags.isArray());
        assertThat(tags.size(), is(1));
        assertTrue(tags.get(0).isTextual());
    }

    @Test
    public void shouldUnwrapNonNullTypes() throws Exception {
        String schema = "type Query { user: User! } " +
            "type User { id: ID! name: String! tags: [String!]! }";
        JsonNode data = synthesize(schema, "{ user { id name tags } }").get("data");

        JsonNode user = data.get("user");
        assertFalse("non-null object must not be null", user.isNull());
        assertTrue(user.get("id").isTextual());
        assertTrue(user.get("name").isTextual());
        assertTrue(user.get("tags").isArray());
        assertTrue(user.get("tags").get(0).isTextual());
    }

    @Test
    public void shouldSynthesizeEnumAsFirstValue() throws Exception {
        String schema = "type Query { status: Status } " +
            "enum Status { ACTIVE INACTIVE SUSPENDED }";
        JsonNode data = synthesize(schema, "{ status }").get("data");

        assertThat(data.get("status").asText(), is("ACTIVE"));
    }

    @Test
    public void shouldHonourFieldAliases() throws Exception {
        String schema = "type Query { name: String }";
        JsonNode data = synthesize(schema, "{ alias: name }").get("data");

        assertTrue("aliased key present", data.has("alias"));
        assertFalse("original field name absent when aliased", data.has("name"));
    }

    @Test
    public void shouldSynthesizeTypename() throws Exception {
        String schema = "type Query { user: User } type User { name: String }";
        JsonNode data = synthesize(schema, "{ user { __typename name } }").get("data");

        assertThat(data.get("user").get("__typename").asText(), is("User"));
    }

    @Test
    public void shouldHandleJsonWrappedQueryBody() throws Exception {
        String schema = "type Query { hello: String }";
        String body = "{\"query\":\"{ hello }\"}";
        JsonNode data = synthesize(schema, body).get("data");

        assertTrue(data.get("hello").isTextual());
    }

    @Test
    public void shouldSynthesizeMutationResponse() throws Exception {
        String schema = "type Query { noop: String } " +
            "type Mutation { createUser(name: String): User } " +
            "type User { id: ID name: String }";
        JsonNode data = synthesize(schema, "mutation { createUser(name: \"x\") { id name } }").get("data");

        JsonNode created = data.get("createUser");
        assertTrue(created.get("id").isTextual());
        assertTrue(created.get("name").isTextual());
    }

    @Test
    public void shouldExpandFragmentSpread() throws Exception {
        String schema = "type Query { user: User } type User { id: ID name: String }";
        String query = "{ user { ...userFields } } fragment userFields on User { id name }";
        JsonNode data = synthesize(schema, query).get("data");

        JsonNode user = data.get("user");
        assertTrue(user.has("id"));
        assertTrue(user.has("name"));
    }

    @Test
    public void shouldExpandInlineFragment() throws Exception {
        String schema = "type Query { user: User } type User { id: ID name: String }";
        JsonNode data = synthesize(schema, "{ user { ... on User { id name } } }").get("data");

        JsonNode user = data.get("user");
        assertTrue(user.has("id"));
        assertTrue(user.has("name"));
    }

    @Test
    public void shouldSynthesizeCustomScalarPlaceholders() throws Exception {
        String schema = "scalar DateTime scalar JSON " +
            "type Query { createdAt: DateTime meta: JSON }";
        JsonNode data = synthesize(schema, "{ createdAt meta }").get("data");

        assertTrue(data.get("createdAt").isTextual());
        assertThat(data.get("createdAt").asText(), is("2020-01-01T00:00:00Z"));
        assertTrue(data.get("meta").isObject());
    }

    @Test
    public void shouldSelectNamedOperationFromMultiOperationDocument() throws Exception {
        String schema = "type Query { a: String b: Int }";
        String body = "{\"query\":\"query A { a } query B { b }\",\"operationName\":\"B\"}";
        JsonNode data = synthesize(schema, body).get("data");

        assertTrue("operation B selected", data.has("b"));
        assertFalse("operation A not selected", data.has("a"));
    }

    @Test
    public void shouldSynthesizeFromIntrospectionJson() throws Exception {
        // Build an introspection JSON result from an SDL schema, then feed the JSON back in.
        String sdl = "type Query { hello: String count: Int }";
        String introspectionJson = IntrospectionFixture.introspectionJsonFor(sdl);

        JsonNode data = synthesize(introspectionJson, "{ hello count }").get("data");
        assertTrue(data.get("hello").isTextual());
        assertTrue(data.get("count").isIntegralNumber());
    }

    @Test
    public void shouldTerminateOnCyclicFragmentSpreadWithoutStackOverflow() throws Exception {
        // a self-referential fragment must be bounded by the recursion guard, not recurse forever
        String schema = "type Query { user: User } type User { id: ID user: User }";
        String query = "{ user { ...f } } fragment f on User { id user { ...f } }";

        // must complete (no StackOverflowError) and produce a valid JSON envelope
        JsonNode response = synthesize(schema, query);
        assertTrue(response.has("data"));
    }

    @Test
    public void shouldThrowOnBlankSchema() {
        try {
            new GraphQLResponseSynthesizer("   ");
            fail("expected GraphQLSchemaException");
        } catch (GraphQLSchemaException expected) {
            // ok
        }
    }

    @Test
    public void shouldThrowOnInvalidQuery() {
        GraphQLResponseSynthesizer synthesizer =
            new GraphQLResponseSynthesizer("type Query { hello: String }");
        try {
            synthesizer.synthesizeResponse("{ hello ");
            fail("expected GraphQLSchemaException");
        } catch (GraphQLSchemaException expected) {
            // ok
        }
    }
}
