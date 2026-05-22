package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.NO_MATCH_RESPONSE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test that uses HttpState directly to:
 * 1. Add expectations and log NO_MATCH_RESPONSE entries
 * 2. Call explainUnmatched() and the MCP tool
 * 3. Verify ranked diagnostics with remediation hints are returned
 */
public class ExplainUnmatchedIntegrationTest {

    private HttpState httpState;
    private McpToolRegistry toolRegistry;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);

        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    @Test
    public void shouldExplainUnmatchedRequestViaRestEndpoint() throws Exception {
        // given - create an expectation
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "POST");
        createParams.put("path", "/api/users");
        createParams.put("statusCode", 201);
        toolRegistry.callTool("create_expectation", createParams);

        // simulate an unmatched request by logging a NO_MATCH_RESPONSE entry
        httpState.log(new LogEntry()
            .setType(NO_MATCH_RESPONSE)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/orders"))
            .setHttpResponse(response().withStatusCode(404))
            .setMessageFormat("no expectation for:{}returning response:{}")
            .setArguments(request().withMethod("GET").withPath("/api/orders"), response().withStatusCode(404))
        );

        // allow async disruptor to process
        Thread.sleep(500);

        // when - call explainUnmatched
        HttpResponse explainResponse = httpState.explainUnmatched(
            request().withMethod("PUT").withBody("{\"limit\":10}")
        );

        // then
        assertThat(explainResponse.getStatusCode(), is(200));
        JsonNode result = objectMapper.readTree(explainResponse.getBodyAsString());

        assertThat(result.has("unmatchedRequestCount"), is(true));
        assertThat("expected at least 1 unmatched request, got: " + result,
            result.path("unmatchedRequestCount").asInt() >= 1, is(true));
        assertThat(result.path("unmatchedRequests").isArray(), is(true));

        // verify the unmatched request has closest expectations with ranked diffs
        JsonNode unmatchedRequests = result.path("unmatchedRequests");
        boolean foundOurRequest = false;
        for (JsonNode unmatchedReq : unmatchedRequests) {
            if ("/api/orders".equals(unmatchedReq.path("path").asText())) {
                foundOurRequest = true;
                assertThat(unmatchedReq.has("closestExpectations"), is(true));
                assertThat(unmatchedReq.path("closestExpectations").isArray(), is(true));
                assertThat(unmatchedReq.path("closestExpectations").size() >= 1, is(true));

                // the closest expectation should have differences and remediation
                JsonNode closestExp = unmatchedReq.path("closestExpectations").get(0);
                assertThat(closestExp.has("expectationId"), is(true));
                assertThat(closestExp.has("differences"), is(true));
                assertThat(closestExp.has("remediation"), is(true));
                assertThat(closestExp.path("differingFieldCount").asInt() >= 1, is(true));
                break;
            }
        }
        assertThat("should find our unmatched /api/orders request", foundOurRequest, is(true));
    }

    @Test
    public void shouldExplainUnmatchedViaMcpToolWithRankedResults() throws Exception {
        // given - create two expectations with different paths and methods
        ObjectNode createParams1 = objectMapper.createObjectNode();
        createParams1.put("method", "POST");
        createParams1.put("path", "/api/users");
        createParams1.put("statusCode", 201);
        toolRegistry.callTool("create_expectation", createParams1);

        ObjectNode createParams2 = objectMapper.createObjectNode();
        createParams2.put("method", "DELETE");
        createParams2.put("path", "/api/items");
        createParams2.put("statusCode", 204);
        toolRegistry.callTool("create_expectation", createParams2);

        // simulate an unmatched request (GET /api/users - close to the POST /api/users expectation)
        httpState.log(new LogEntry()
            .setType(NO_MATCH_RESPONSE)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(404))
            .setMessageFormat("no expectation for:{}returning response:{}")
            .setArguments(request().withMethod("GET").withPath("/api/users"), response().withStatusCode(404))
        );

        Thread.sleep(500);

        // when - call the MCP tool
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 10);
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("unmatchedRequestCount").asInt() >= 1, is(true));

        JsonNode unmatchedRequests = result.path("unmatchedRequests");
        boolean foundRequest = false;
        for (JsonNode unmatchedReq : unmatchedRequests) {
            if ("/api/users".equals(unmatchedReq.path("path").asText())
                && "GET".equals(unmatchedReq.path("method").asText())) {
                foundRequest = true;
                JsonNode closestExpectations = unmatchedReq.path("closestExpectations");
                assertThat(closestExpectations.size() >= 2, is(true));

                // first result should be the closest match (POST /api/users - differs only by method)
                JsonNode closest = closestExpectations.get(0);
                int firstDiff = closest.path("differingFieldCount").asInt();
                int secondDiff = closestExpectations.get(1).path("differingFieldCount").asInt();
                assertThat("results should be ranked closest first", firstDiff <= secondDiff, is(true));

                // verify remediation hints are present
                assertThat(closest.has("remediation"), is(true));
                break;
            }
        }
        assertThat("should find unmatched GET /api/users request", foundRequest, is(true));
    }

    @Test
    public void shouldExplainUnmatchedWhenNoUnmatchedRequests() throws Exception {
        // given - no unmatched requests logged

        // when
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("unmatchedRequestCount").asInt(), is(0));
        assertThat(result.path("unmatchedRequests").size(), is(0));
    }

    @Test
    public void shouldReturnRemediationHintsForMethodMismatch() throws Exception {
        // given
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "POST");
        createParams.put("path", "/api/data");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        // simulate sending GET instead of POST
        httpState.log(new LogEntry()
            .setType(NO_MATCH_RESPONSE)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/data"))
            .setHttpResponse(response().withStatusCode(404))
            .setMessageFormat("no expectation for:{}returning response:{}")
            .setArguments(request().withMethod("GET").withPath("/api/data"), response().withStatusCode(404))
        );

        Thread.sleep(500);

        // when
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("unmatchedRequestCount").asInt() >= 1, is(true));
        JsonNode unmatchedReq = result.path("unmatchedRequests").get(0);
        JsonNode closestExp = unmatchedReq.path("closestExpectations").get(0);

        // should have method difference
        assertThat(closestExp.has("differences"), is(true));
        assertThat(closestExp.path("differences").has("method"), is(true));

        // should have remediation hint for method
        assertThat(closestExp.has("remediation"), is(true));
        assertThat(closestExp.path("remediation").has("method"), is(true));
    }

    @Test
    public void shouldSetTruncatedFlagWhenEvaluationBudgetExceeded() throws Exception {
        // given - create many expectations to exceed the evaluation budget
        // Budget is 500, so with 50 expectations (max per request) and 11 unmatched requests,
        // we need 50 * 11 = 550 evaluations which exceeds the 500 budget.
        // After 10 requests (50 * 10 = 500 evaluations), the budget is exactly exhausted.
        // The 11th request triggers truncation immediately and the outer loop breaks.
        for (int i = 0; i < 50; i++) {
            ObjectNode createParams = objectMapper.createObjectNode();
            createParams.put("method", "POST");
            createParams.put("path", "/api/resource-" + i);
            createParams.put("statusCode", 200);
            toolRegistry.callTool("create_expectation", createParams);
        }

        // simulate 20 unmatched requests (only some will be fully evaluated)
        for (int i = 0; i < 20; i++) {
            httpState.log(new LogEntry()
                .setType(NO_MATCH_RESPONSE)
                .setLogLevel(org.slf4j.event.Level.INFO)
                .setHttpRequest(request().withMethod("GET").withPath("/api/unmatched-" + i))
                .setHttpResponse(response().withStatusCode(404))
                .setMessageFormat("no expectation for:{}returning response:{}")
                .setArguments(
                    request().withMethod("GET").withPath("/api/unmatched-" + i),
                    response().withStatusCode(404)
                )
            );
        }

        Thread.sleep(500);

        // when - request all 20 unmatched
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 20);
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then - should have truncated flag set
        assertThat("should indicate truncation when budget exceeded",
            result.path("truncated").asBoolean(), is(true));
        // should still have some unmatched requests processed
        assertThat(result.path("unmatchedRequestCount").asInt() >= 1, is(true));
        // but should not have all 20 fully processed
        assertThat("should not have evaluated all requests",
            result.path("unmatchedRequestCount").asInt() < 20, is(true));
    }

    @Test
    public void shouldNotSetTruncatedFlagWhenWithinBudget() throws Exception {
        // given - few expectations and few unmatched requests (well within budget)
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "POST");
        createParams.put("path", "/api/single");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        httpState.log(new LogEntry()
            .setType(NO_MATCH_RESPONSE)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/single"))
            .setHttpResponse(response().withStatusCode(404))
            .setMessageFormat("no expectation for:{}returning response:{}")
            .setArguments(
                request().withMethod("GET").withPath("/api/single"),
                response().withStatusCode(404)
            )
        );

        Thread.sleep(500);

        // when
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 10);
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat("should not truncate within budget",
            result.path("truncated").asBoolean(), is(false));
        assertThat(result.path("unmatchedRequestCount").asInt(), is(1));
    }
}
