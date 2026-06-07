package org.mockserver.mock.pact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

public class PactExporterTest {

    private final PactExporter exporter = new PactExporter();
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private JsonNode exportAndParse(List<Expectation> expectations, String consumer, String provider) throws Exception {
        return objectMapper.readTree(exporter.export(expectations, consumer, provider));
    }

    @Test
    public void exportsResponseExpectationAsPactInteraction() throws Exception {
        Expectation getUsers = new Expectation(
            request().withMethod("GET").withPath("/users")
                .withQueryStringParameter("page", "1")
                .withHeader("Accept", "application/json")
        ).withId("getUsers").thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"users\":[]}")
        );

        JsonNode pact = exportAndParse(Collections.singletonList(getUsers), "MyConsumer", "MyProvider");

        assertEquals("MyConsumer", pact.at("/consumer/name").asText());
        assertEquals("MyProvider", pact.at("/provider/name").asText());
        assertEquals("3.0.0", pact.at("/metadata/pactSpecification/version").asText());

        JsonNode interactions = pact.get("interactions");
        assertEquals(1, interactions.size());
        JsonNode interaction = interactions.get(0);
        assertEquals("getUsers", interaction.get("description").asText());
        assertEquals("GET", interaction.at("/request/method").asText());
        assertEquals("/users", interaction.at("/request/path").asText());
        assertEquals("1", interaction.at("/request/query/page/0").asText());
        assertEquals("application/json", interaction.at("/request/headers/Accept/0").asText());
        assertEquals(200, interaction.at("/response/status").asInt());
        assertEquals("application/json", interaction.at("/response/headers/Content-Type/0").asText());
        // JSON response body is embedded as structured JSON, not an escaped string
        assertTrue(interaction.at("/response/body/users").isArray());
    }

    @Test
    public void skipsExpectationsWithoutResponseAction() throws Exception {
        Expectation withResponse = new Expectation(request().withPath("/a"))
            .thenRespond(response().withStatusCode(204));
        Expectation withoutAction = new Expectation(request().withPath("/b")); // no action

        JsonNode pact = exportAndParse(Arrays.asList(withResponse, withoutAction), "c", "p");

        assertEquals(1, pact.get("interactions").size());
        assertEquals("/a", pact.at("/interactions/0/request/path").asText());
    }

    @Test
    public void embedsJsonRequestBodyAsStructuredJson() throws Exception {
        Expectation create = new Expectation(
            request().withMethod("POST").withPath("/users").withBody(json("{\"name\":\"alice\"}"))
        ).thenRespond(response().withStatusCode(201));

        JsonNode pact = exportAndParse(Collections.singletonList(create), "c", "p");

        assertEquals("alice", pact.at("/interactions/0/request/body/name").asText());
    }

    @Test
    public void defaultsConsumerAndProviderNamesWhenBlank() throws Exception {
        Expectation e = new Expectation(request().withPath("/x"))
            .thenRespond(response().withStatusCode(200));

        JsonNode pact = exportAndParse(Collections.singletonList(e), "  ", null);

        assertEquals("consumer", pact.at("/consumer/name").asText());
        assertEquals("provider", pact.at("/provider/name").asText());
    }

    @Test
    public void exportsFirstResponseOfASequence() throws Exception {
        Expectation seq = new Expectation(request().withMethod("GET").withPath("/seq"))
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ));

        JsonNode pact = exportAndParse(Collections.singletonList(seq), "c", "p");

        assertEquals(1, pact.get("interactions").size());
        assertEquals(200, pact.at("/interactions/0/response/status").asInt());
    }

    @Test
    public void skipsExpectationWithNottedMethodMatcher() throws Exception {
        // "match anything except GET" has no positive Pact equivalent and must be skipped
        Expectation notted = new Expectation(
            request().withMethod(org.mockserver.model.NottableString.not("GET")).withPath("/x")
        ).thenRespond(response().withStatusCode(200));

        JsonNode pact = exportAndParse(Collections.singletonList(notted), "c", "p");

        assertEquals(0, pact.get("interactions").size());
    }

    @Test
    public void usesMethodPathDescriptionWhenIdIsUuid() throws Exception {
        Expectation e = new Expectation(request().withMethod("GET").withPath("/x"))
            .withId("550e8400-e29b-41d4-a716-446655440000")
            .thenRespond(response().withStatusCode(200));

        JsonNode pact = exportAndParse(Collections.singletonList(e), "c", "p");

        assertEquals("GET /x", pact.at("/interactions/0/description").asText());
    }

    @Test
    public void omitsQueryAndHeadersWhenAbsent() throws Exception {
        Expectation e = new Expectation(request().withMethod("GET").withPath("/ping"))
            .thenRespond(response().withStatusCode(200));

        JsonNode request = exportAndParse(Collections.singletonList(e), "c", "p").at("/interactions/0/request");

        assertFalse(request.has("query"));
        assertFalse(request.has("headers"));
    }
}
