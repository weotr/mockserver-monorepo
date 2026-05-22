package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test that:
 * 1. Starts a real MockServer instance
 * 2. Seeds it with expectations that satisfy (and some that violate) a known OpenAPI spec
 * 3. Runs run_contract_test against that instance's base URL
 * 4. Exercises verify_traffic_against_openapi over recorded traffic
 */
public class OpenApiVerificationIntegrationTest {

    // Simple inline spec for integration testing
    private static final String SPEC = "{\n" +
        "  \"openapi\": \"3.0.0\",\n" +
        "  \"info\": {\"title\": \"Test API\", \"version\": \"1.0\"},\n" +
        "  \"paths\": {\n" +
        "    \"/api/items\": {\n" +
        "      \"get\": {\n" +
        "        \"operationId\": \"listItems\",\n" +
        "        \"responses\": {\n" +
        "          \"200\": {\n" +
        "            \"description\": \"OK\",\n" +
        "            \"content\": {\n" +
        "              \"application/json\": {\n" +
        "                \"schema\": {\n" +
        "                  \"type\": \"array\",\n" +
        "                  \"items\": {\n" +
        "                    \"type\": \"object\",\n" +
        "                    \"required\": [\"id\", \"name\"],\n" +
        "                    \"properties\": {\n" +
        "                      \"id\": {\"type\": \"integer\"},\n" +
        "                      \"name\": {\"type\": \"string\"}\n" +
        "                    }\n" +
        "                  }\n" +
        "                }\n" +
        "              }\n" +
        "            }\n" +
        "          }\n" +
        "        }\n" +
        "      },\n" +
        "      \"post\": {\n" +
        "        \"operationId\": \"createItem\",\n" +
        "        \"requestBody\": {\n" +
        "          \"required\": true,\n" +
        "          \"content\": {\n" +
        "            \"application/json\": {\n" +
        "              \"schema\": {\n" +
        "                \"type\": \"object\",\n" +
        "                \"required\": [\"name\"],\n" +
        "                \"properties\": {\n" +
        "                  \"name\": {\"type\": \"string\"}\n" +
        "                }\n" +
        "              }\n" +
        "            }\n" +
        "          }\n" +
        "        },\n" +
        "        \"responses\": {\n" +
        "          \"201\": {\n" +
        "            \"description\": \"Created\",\n" +
        "            \"content\": {\n" +
        "              \"application/json\": {\n" +
        "                \"schema\": {\n" +
        "                  \"type\": \"object\",\n" +
        "                  \"required\": [\"id\", \"name\"],\n" +
        "                  \"properties\": {\n" +
        "                    \"id\": {\"type\": \"integer\"},\n" +
        "                    \"name\": {\"type\": \"string\"}\n" +
        "                  }\n" +
        "                }\n" +
        "              }\n" +
        "            }\n" +
        "          }\n" +
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
        // Reset mock server state
        mockServer.reset();

        // Create a separate HttpState for MCP tool registry (for verify_traffic tool)
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(mockServerPort));
        when(server.isRunning()).thenReturn(true);

        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    @Test
    public void shouldPassContractTestWhenExpectationsConform() {
        // given - seed MockServer with expectations that conform to the spec
        mockServer.when(
            request().withMethod("GET").withPath("/api/items")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Widget\"}]")
        );

        mockServer.when(
            request().withMethod("POST").withPath("/api/items")
        ).respond(
            response()
                .withStatusCode(201)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 2, \"name\": \"some_string_value\"}")
        );

        // when - run contract test
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);

        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then
        assertThat(result.path("status").asText(), is("all_passed"));
        assertThat(result.path("totalOperations").asInt(), is(2));
        assertThat(result.path("passed").asInt(), is(2));
        assertThat(result.path("failed").asInt(), is(0));

        // verify individual results
        JsonNode results = result.path("results");
        assertThat(results.isArray(), is(true));
        for (JsonNode opResult : results) {
            assertThat(opResult.path("passed").asBoolean(), is(true));
        }
    }

    @Test
    public void shouldFailContractTestWhenResponseViolatesSpec() {
        // given - seed MockServer with a conforming GET but violating POST response
        mockServer.when(
            request().withMethod("GET").withPath("/api/items")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Widget\"}]")
        );

        // POST returns a 200 with wrong body schema instead of 201 with correct schema
        mockServer.when(
            request().withMethod("POST").withPath("/api/items")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"wrong\": \"field\"}")
        );

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);

        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then
        assertThat(result.path("status").asText(), is("failures_detected"));
        assertThat(result.path("failed").asInt() >= 1, is(true));

        // find the failing operation
        JsonNode results = result.path("results");
        boolean foundFailure = false;
        for (JsonNode opResult : results) {
            if (!opResult.path("passed").asBoolean()) {
                foundFailure = true;
                assertThat(opResult.has("validationErrors"), is(true));
                assertThat(opResult.path("validationErrors").size() >= 1, is(true));
            }
        }
        assertThat("should find at least one failing operation", foundFailure, is(true));
    }

    @Test
    public void shouldFilterContractTestByOperationId() {
        // given
        mockServer.when(
            request().withMethod("GET").withPath("/api/items")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Widget\"}]")
        );

        // when - run only for listItems
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("baseUrl", "http://localhost:" + mockServerPort);
        params.put("operationId", "listItems");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then - should only test 1 operation
        assertThat(result.path("totalOperations").asInt(), is(1));
        assertThat(result.path("results").get(0).path("operationId").asText(), is("listItems"));
    }

    @Test
    public void shouldVerifyTrafficAgainstOpenApiWithConformingTraffic() throws Exception {
        // given - simulate recorded traffic via the HttpState event log
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/items"))
            .setHttpResponse(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Widget\"}]"))
            .setExpectation(request().withMethod("GET").withPath("/api/items"),
                response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("[{\"id\": 1, \"name\": \"Widget\"}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);

        // then
        assertThat(result.path("status").asText(), is("conformant"));
        assertThat(result.path("totalPairs").asInt() >= 1, is(true));
        assertThat(result.path("passed").asInt() >= 1, is(true));
        assertThat(result.path("failed").asInt(), is(0));
    }

    @Test
    public void shouldVerifyTrafficAgainstOpenApiWithNonConformingTraffic() throws Exception {
        // given - simulate recorded traffic with a non-conforming response
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/items"))
            .setHttpResponse(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"not\": \"an array\"}"))
            .setExpectation(request().withMethod("GET").withPath("/api/items"),
                response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("{\"not\": \"an array\"}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);

        // then
        assertThat(result.path("status").asText(), is("non_conformant"));
        assertThat(result.path("failed").asInt() >= 1, is(true));

        // Check that response errors are reported
        JsonNode resultsArray = result.path("results");
        boolean foundFailure = false;
        for (JsonNode pairResult : resultsArray) {
            if (!pairResult.path("passed").asBoolean()) {
                foundFailure = true;
                assertThat(pairResult.has("responseErrors"), is(true));
            }
        }
        assertThat("should find non-conformant pair", foundFailure, is(true));
    }

    @Test
    public void shouldVerifyTrafficWithQueryParamsCookiesAndMultiValueHeaders() throws Exception {
        // given - simulate recorded traffic that includes query params, cookies, and multi-value headers
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request()
                .withMethod("GET")
                .withPath("/api/items")
                .withQueryStringParameter("page", "1")
                .withQueryStringParameter("size", "10")
                .withCookie("sessionId", "abc123")
                .withHeader("Accept", "application/json", "text/plain")
                .withHeader("X-Request-Id", "req-42"))
            .setHttpResponse(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withHeader("Cache-Control", "no-cache", "no-store")
                .withBody("[{\"id\": 1, \"name\": \"Widget\"}]"))
            .setExpectation(request().withMethod("GET").withPath("/api/items"),
                response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("[{\"id\": 1, \"name\": \"Widget\"}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);

        // then - traffic should be parsed correctly and validated (the request/response is valid)
        assertThat(result.path("status").asText(), is("conformant"));
        assertThat(result.path("totalPairs").asInt(), is(1));
        assertThat(result.path("passed").asInt(), is(1));
        assertThat(result.path("failed").asInt(), is(0));
    }

    @Test
    public void shouldVerifyTrafficWithMethodFilter() throws Exception {
        // given - record both GET and POST traffic
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/items"))
            .setHttpResponse(response().withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Widget\"}]"))
            .setExpectation(request().withMethod("GET").withPath("/api/items"),
                response().withStatusCode(200).withBody("[{\"id\": 1, \"name\": \"Widget\"}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/api/items")
                .withHeader("content-type", "application/json")
                .withBody("{\"name\": \"NewItem\"}"))
            .setHttpResponse(response().withStatusCode(201)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 2, \"name\": \"NewItem\"}"))
            .setExpectation(request().withMethod("POST").withPath("/api/items"),
                response().withStatusCode(201).withBody("{\"id\": 2, \"name\": \"NewItem\"}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(201))
        );

        Thread.sleep(500);

        // when - filter by GET only
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", SPEC);
        params.put("method", "GET");

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);

        // then - should only validate GET traffic
        assertThat(result.path("totalPairs").asInt(), is(1));
        assertThat(result.path("results").get(0).path("method").asText(), is("GET"));
    }
}
