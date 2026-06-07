package org.mockserver.mock.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.NottableString;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class McpToolSchemaGeneratorTest {

    private final McpToolSchemaGenerator generator = new McpToolSchemaGenerator();

    @Test
    public void generatesToolFromResponseExpectation() {
        Expectation getUsers = new Expectation(
            request().withMethod("GET").withPath("/users")
                .withQueryStringParameter("page", "1")
        ).withId("getUsers").thenRespond(response().withStatusCode(200).withBody("[]"));

        ArrayNode tools = generator.generate(Collections.singletonList(getUsers));

        assertEquals(1, tools.size());
        JsonNode tool = tools.get(0);
        assertEquals("get_users", tool.get("name").asText());
        assertEquals("Mock for GET /users", tool.get("description").asText());
        assertEquals("object", tool.at("/inputSchema/type").asText());
        assertEquals("string", tool.at("/inputSchema/properties/page/type").asText());
        assertEquals("GET", tool.at("/_mockserver/method").asText());
        assertEquals("/users", tool.at("/_mockserver/path").asText());
        assertEquals("getUsers", tool.at("/_mockserver/expectationId").asText());
    }

    @Test
    public void exposesRequestBodyInInputSchema() {
        Expectation create = new Expectation(
            request().withMethod("POST").withPath("/users").withBody("{}")
        ).thenRespond(response().withStatusCode(201));

        JsonNode tool = generator.generate(Collections.singletonList(create)).get(0);
        assertTrue(tool.at("/inputSchema/properties/body").isObject());
    }

    @Test
    public void sanitizesAndDeduplicatesToolNames() {
        Expectation a = new Expectation(request().withMethod("GET").withPath("/users/{id}"))
            .thenRespond(response().withStatusCode(200));
        Expectation b = new Expectation(request().withMethod("GET").withPath("/users/{id}"))
            .thenRespond(response().withStatusCode(200));

        ArrayNode tools = generator.generate(Arrays.asList(a, b));

        assertEquals("get_users_id", tools.get(0).get("name").asText());
        assertEquals("get_users_id_2", tools.get(1).get("name").asText());
    }

    @Test
    public void skipsNottedMethodMatcher() {
        Expectation notted = new Expectation(
            request().withMethod(NottableString.not("GET")).withPath("/x")
        ).thenRespond(response().withStatusCode(200));

        assertEquals(0, generator.generate(Collections.singletonList(notted)).size());
    }

    @Test
    public void skipsExpectationWithoutResponseAction() {
        Expectation noAction = new Expectation(request().withPath("/x")); // no action

        assertEquals(0, generator.generate(Collections.singletonList(noAction)).size());
    }

    @Test
    public void omitsBodyPropertyWhenNoRequestBody() {
        Expectation e = new Expectation(request().withMethod("GET").withPath("/ping"))
            .thenRespond(response().withStatusCode(200));

        JsonNode tool = generator.generate(Collections.singletonList(e)).get(0);
        assertFalse(tool.at("/inputSchema/properties").has("body"));
    }

    @Test
    public void skipsNottedPathMatcher() {
        Expectation notted = new Expectation(
            request().withMethod("GET").withPath(NottableString.not("/x"))
        ).thenRespond(response().withStatusCode(200));

        assertEquals(0, generator.generate(Collections.singletonList(notted)).size());
    }

    @Test
    public void deduplicatedNameStaysWithinSixtyFourChars() {
        String longPath = "/" + "a".repeat(70);
        Expectation a = new Expectation(request().withMethod("GET").withPath(longPath))
            .thenRespond(response().withStatusCode(200));
        Expectation b = new Expectation(request().withMethod("GET").withPath(longPath))
            .thenRespond(response().withStatusCode(200));

        ArrayNode tools = generator.generate(Arrays.asList(a, b));

        assertTrue(tools.get(0).get("name").asText().length() <= 64);
        assertTrue(tools.get(1).get("name").asText().length() <= 64);
        // the two names must still be distinct after truncation
        assertFalse(tools.get(0).get("name").asText().equals(tools.get(1).get("name").asText()));
    }

    @Test
    public void returnsEmptyForNullExpectations() {
        assertEquals(0, generator.generate(null).size());
    }
}
