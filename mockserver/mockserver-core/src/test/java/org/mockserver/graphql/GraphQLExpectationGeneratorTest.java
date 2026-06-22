package org.mockserver.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.GraphQLMatcher;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.http.HttpResponseActionHandler;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;

/**
 * Tests for {@link GraphQLExpectationGenerator}, the GraphQL SDL / introspection import.
 */
public class GraphQLExpectationGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphQLExpectationGenerator generator = new GraphQLExpectationGenerator();
    private final HttpResponseActionHandler responseActionHandler =
        new HttpResponseActionHandler(new MockServerLogger(), new Configuration());

    private static final String SDL =
        "type Query { user(id: ID): User hello: String } " +
        "type Mutation { createUser(name: String): User } " +
        "type User { id: ID name: String email: String age: Int }";

    @Test
    public void shouldGenerateOneExpectationPerRootOperationType() {
        // when
        List<Expectation> expectations = generator.generate(SDL);

        // then - one for Query, one for Mutation (no Subscription in the SDL)
        assertThat(expectations, hasSize(2));
        for (Expectation expectation : expectations) {
            HttpRequest httpRequest = (HttpRequest) expectation.getHttpRequest();
            assertThat(httpRequest.getMethod().getValue(), is("POST"));
            assertThat(httpRequest.getPath().getValue(), is("/graphql"));
            assertThat(httpRequest.getBody(), instanceOf(GraphQLBody.class));
            GraphQLBody body = (GraphQLBody) httpRequest.getBody();
            assertThat(body.getSchema(), is(SDL));
            // response has no body so synthesis kicks in per query
            assertThat(expectation.getHttpResponse().getBody(), is(nullValue()));
        }
    }

    @Test
    public void shouldHonourCustomPath() {
        List<Expectation> expectations = generator.generate(SDL, "/api/graphql");
        assertThat(((HttpRequest) expectations.get(0).getHttpRequest()).getPath().getValue(), is("/api/graphql"));
    }

    @Test
    public void shouldGenerateExpectationThatMatchesAnyQueryAndSynthesizesResponse() throws Exception {
        // given - import the SDL
        Expectation queryExpectation = generator.generate(SDL).stream()
            .filter(e -> ((GraphQLBody) ((HttpRequest) e.getHttpRequest()).getBody()).getQuery().startsWith("query"))
            .findFirst().orElseThrow();
        GraphQLBody graphQLBody = (GraphQLBody) ((HttpRequest) queryExpectation.getHttpRequest()).getBody();

        // when - an arbitrary query against the schema is matched
        GraphQLMatcher matcher = new GraphQLMatcher(new MockServerLogger(), graphQLBody);
        boolean matched = matcher.matches(null, "{\"query\":\"{ user(id: 1) { name email } }\"}");

        // then - the generated expectation matches the (different) query
        assertTrue("imported query expectation should match any query against the schema", matched);

        // and - the response is synthesized from the schema for that specific query
        HttpResponse synthesized = responseActionHandler.handle(
            queryExpectation.getHttpResponse(),
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user(id: 1) { name email } }\"}"),
            queryExpectation.getHttpRequest());

        assertThat(synthesized.getBody().getContentType(), containsString("application/json"));
        JsonNode user = MAPPER.readTree(synthesized.getBodyAsString()).get("data").get("user");
        assertTrue("name requested", user.has("name"));
        assertTrue("email requested", user.has("email"));
        assertThat("age not requested", user.get("age"), is(nullValue()));
    }

    @Test
    public void shouldGenerateMatchingMutationExpectation() {
        Expectation mutationExpectation = generator.generate(SDL).stream()
            .filter(e -> ((GraphQLBody) ((HttpRequest) e.getHttpRequest()).getBody()).getQuery().startsWith("mutation"))
            .findFirst().orElseThrow();
        GraphQLBody graphQLBody = (GraphQLBody) ((HttpRequest) mutationExpectation.getHttpRequest()).getBody();

        GraphQLMatcher matcher = new GraphQLMatcher(new MockServerLogger(), graphQLBody);
        assertTrue("imported mutation expectation should match a mutation",
            matcher.matches(null, "{\"query\":\"mutation { createUser(name: \\\"a\\\") { id } }\"}"));
        assertThat("mutation expectation should not match a query",
            matcher.matches(null, "{\"query\":\"{ user(id: 1) { name } }\"}"), is(false));
    }

    @Test
    public void shouldImportFromIntrospectionJson() {
        // given - the smallest introspection JSON envelope with a Query root
        String introspection = "{\"data\":{\"__schema\":{" +
            "\"queryType\":{\"name\":\"Query\"}," +
            "\"types\":[{\"kind\":\"OBJECT\",\"name\":\"Query\",\"fields\":[" +
            "{\"name\":\"hello\",\"args\":[],\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}}]," +
            "\"interfaces\":[]}]}}}";

        List<Expectation> expectations = generator.generate(introspection);

        assertThat(expectations, hasSize(1));
        assertThat(((GraphQLBody) ((HttpRequest) expectations.get(0).getHttpRequest()).getBody()).getSchema(), is(introspection));
    }

    @Test
    public void shouldRejectBlankSchema() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generator.generate("  "));
        assertThat(e.getMessage(), containsString("null or blank"));
    }

    @Test
    public void shouldRejectMalformedSchema() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> generator.generate("type Query { this is not valid sdl"));
        assertThat(e.getMessage(), containsString("Unable to parse GraphQL schema"));
    }

    @Test
    public void shouldRejectSchemaWithNoRootTypes() {
        // a schema with only a plain type and no Query/Mutation/Subscription root is rejected
        // by graphql-java up front (a schema MUST define a query operation)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> generator.generate("type Widget { id: ID }"));
        assertThat(e.getMessage(), containsString("Unable to parse GraphQL schema"));
    }
}
