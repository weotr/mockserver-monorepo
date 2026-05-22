package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test for run_resiliency_test MCP tool.
 * <p>
 * Starts a real MockServer instance as the target service, seeds expectations
 * so that some malformed inputs get a 4xx (handled) and some get a 2xx/5xx (unexpected),
 * runs run_resiliency_test against it, and asserts the classifications and summary.
 */
public class OpenApiResiliencyIntegrationTest {

    // Spec with a POST endpoint that has required fields, numeric bounds, and string constraints
    private static final String SPEC = "{\n" +
        "  \"openapi\": \"3.0.0\",\n" +
        "  \"info\": {\"title\": \"Test API\", \"version\": \"1.0\"},\n" +
        "  \"paths\": {\n" +
        "    \"/api/widgets\": {\n" +
        "      \"post\": {\n" +
        "        \"operationId\": \"createWidget\",\n" +
        "        \"requestBody\": {\n" +
        "          \"required\": true,\n" +
        "          \"content\": {\n" +
        "            \"application/json\": {\n" +
        "              \"schema\": {\n" +
        "                \"type\": \"object\",\n" +
        "                \"required\": [\"name\", \"count\"],\n" +
        "                \"properties\": {\n" +
        "                  \"name\": {\n" +
        "                    \"type\": \"string\",\n" +
        "                    \"minLength\": 2,\n" +
        "                    \"maxLength\": 100\n" +
        "                  },\n" +
        "                  \"count\": {\n" +
        "                    \"type\": \"integer\",\n" +
        "                    \"minimum\": 0,\n" +
        "                    \"maximum\": 999\n" +
        "                  },\n" +
        "                  \"notes\": {\n" +
        "                    \"type\": \"string\"\n" +
        "                  }\n" +
        "                }\n" +
        "              }\n" +
        "            }\n" +
        "          }\n" +
        "        },\n" +
        "        \"responses\": {\n" +
        "          \"201\": { \"description\": \"Created\" },\n" +
        "          \"400\": { \"description\": \"Bad request\" }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    private static ClientAndServer mockServer;
    private static int mockServerPort;

    private HttpState httpState;
    private McpToolRegistry toolRegistry;
    private ObjectMapper objectMapper;

    @BeforeClass
    public static void startServer() {
        mockServer = ClientAndServer.startClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @AfterClass
    public static void stopServer() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Before
    public void setUp() {
        mockServer.reset();

        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(mockServerPort));
        when(server.isRunning()).thenReturn(true);

        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    @Test
    public void shouldDetectHandledAndUnexpectedClassifications() {
        // given - seed MockServer:
        // POST /api/widgets with malformed body returns 400 (handled)
        // but POST /api/widgets with any body returns 201 by default (unexpected for malformed input)
        // We set up a specific 400 for unparseable JSON, and a catch-all 201 for everything else
        mockServer.when(
            request().withMethod("POST").withPath("/api/widgets")
        ).respond(
            response()
                .withStatusCode(201)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 1, \"name\": \"widget\", \"count\": 1}")
        );

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then - since all mutations get a 201 (2xx), they should all be UNEXPECTED
        assertThat(result.path("status").asText(), is("failures_detected"));
        assertThat(result.path("totalMutations").asInt(), greaterThan(0));
        assertThat(result.path("unexpected").asInt(), greaterThan(0));

        // verify results array has entries
        JsonNode results = result.path("results");
        assertThat(results.isArray(), is(true));
        assertThat(results.size(), greaterThan(0));

        // verify each result has the expected fields
        for (JsonNode mutation : results) {
            assertThat(mutation.has("operationId"), is(true));
            assertThat(mutation.has("method"), is(true));
            assertThat(mutation.has("path"), is(true));
            assertThat(mutation.has("mutationType"), is(true));
            assertThat(mutation.has("mutationDescription"), is(true));
            assertThat(mutation.has("statusCode"), is(true));
            assertThat(mutation.has("classification"), is(true));
            assertThat(mutation.path("classification").asText(), is("UNEXPECTED"));
            assertThat(mutation.path("statusCode").asInt(), is(201));
        }

        // verify operation summaries
        JsonNode summaries = result.path("operationSummaries");
        assertThat(summaries.isArray(), is(true));
        assertThat(summaries.size(), is(1));
        assertThat(summaries.get(0).path("operationId").asText(), is("createWidget"));
        assertThat(summaries.get(0).path("unexpected").asInt(), greaterThan(0));
    }

    @Test
    public void shouldReportHandledWhenServiceRejects() {
        // given - seed MockServer to return 400 for all requests (simulating good validation)
        mockServer.when(
            request().withMethod("POST").withPath("/api/widgets")
        ).respond(
            response()
                .withStatusCode(400)
                .withHeader("content-type", "application/json")
                .withBody("{\"error\": \"bad request\"}")
        );

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then - all mutations should be HANDLED
        assertThat(result.path("status").asText(), is("all_handled"));
        assertThat(result.path("handled").asInt(), greaterThan(0));
        assertThat(result.path("unexpected").asInt(), is(0));

        // verify all results classified as HANDLED
        for (JsonNode mutation : result.path("results")) {
            assertThat(mutation.path("classification").asText(), is("HANDLED"));
            assertThat(mutation.path("statusCode").asInt(), is(400));
        }
    }

    @Test
    public void shouldFilterByOperationId() {
        // given
        mockServer.when(
            request().withMethod("POST").withPath("/api/widgets")
        ).respond(
            response().withStatusCode(400)
        );

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);
        params.put("operationId", "createWidget");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then
        assertThat(result.path("totalMutations").asInt(), greaterThan(0));
        for (JsonNode mutation : result.path("results")) {
            assertThat(mutation.path("operationId").asText(), is("createWidget"));
        }
    }

    @Test
    public void shouldReportMixedClassifications() {
        // given - 5xx for some requests, 400 for others
        // We simulate this by making the server return 500 by default
        mockServer.when(
            request().withMethod("POST").withPath("/api/widgets")
        ).respond(
            response().withStatusCode(500)
        );

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then - all should be UNEXPECTED since 5xx
        assertThat(result.path("status").asText(), is("failures_detected"));
        assertThat(result.path("unexpected").asInt(), greaterThan(0));
        assertThat(result.path("handled").asInt(), is(0));

        for (JsonNode mutation : result.path("results")) {
            assertThat(mutation.path("classification").asText(), is("UNEXPECTED"));
        }
    }

    @Test
    public void shouldIncludeAllMutationTypes() {
        // given - the spec has required fields, numeric bounds, string constraints
        mockServer.when(
            request().withMethod("POST").withPath("/api/widgets")
        ).respond(
            response().withStatusCode(400)
        );

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then - verify multiple mutation types are present
        boolean hasOmitBody = false;
        boolean hasTypeViolation = false;
        boolean hasNumericBoundary = false;
        boolean hasStringLength = false;
        boolean hasMalformedJson = false;
        boolean hasOversizedString = false;

        for (JsonNode mutation : result.path("results")) {
            String type = mutation.path("mutationType").asText();
            switch (type) {
                case "OMIT_REQUIRED_BODY_FIELD":
                    hasOmitBody = true;
                    break;
                case "TYPE_VIOLATION":
                    hasTypeViolation = true;
                    break;
                case "NUMERIC_BOUNDARY_VIOLATION":
                    hasNumericBoundary = true;
                    break;
                case "STRING_LENGTH_BOUNDARY_VIOLATION":
                    hasStringLength = true;
                    break;
                case "MALFORMED_JSON_BODY":
                    hasMalformedJson = true;
                    break;
                case "OVERSIZED_STRING_FIELD":
                    hasOversizedString = true;
                    break;
                default:
                    break;
            }
        }

        assertThat("should have OMIT_REQUIRED_BODY_FIELD", hasOmitBody, is(true));
        assertThat("should have TYPE_VIOLATION", hasTypeViolation, is(true));
        assertThat("should have NUMERIC_BOUNDARY_VIOLATION", hasNumericBoundary, is(true));
        assertThat("should have STRING_LENGTH_BOUNDARY_VIOLATION", hasStringLength, is(true));
        assertThat("should have MALFORMED_JSON_BODY", hasMalformedJson, is(true));
        assertThat("should have OVERSIZED_STRING_FIELD", hasOversizedString, is(true));
    }
}
