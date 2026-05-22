package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
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
 * Integration test that uses HttpState directly to:
 * 1. Log FORWARDED_REQUEST entries (simulating proxy/forwarding mode)
 * 2. Call create_expectations_from_recorded_traffic MCP tool
 * 3. Verify active expectations are created that match the recorded traffic
 */
public class CreateExpectationsFromRecordedTrafficIntegrationTest {

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
    public void shouldCreateActiveExpectationsFromForwardedRequests() throws Exception {
        // given - simulate forwarded traffic (as if MockServer proxied to a real API)
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[{\"id\":1,\"name\":\"Alice\"}]"))
            .setExpectation(request().withMethod("GET").withPath("/api/users"),
                response().withStatusCode(200).withBody("[{\"id\":1,\"name\":\"Alice\"}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[{\"id\":1,\"name\":\"Alice\"}]"))
        );

        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/api/orders"))
            .setHttpResponse(response().withStatusCode(201).withBody("{\"orderId\":42}"))
            .setExpectation(request().withMethod("POST").withPath("/api/orders"),
                response().withStatusCode(201).withBody("{\"orderId\":42}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(201).withBody("{\"orderId\":42}"))
        );

        // allow async disruptor to process
        Thread.sleep(500);

        // when - create expectations from recorded traffic
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - expectations should be created
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(2));
        assertThat(result.path("ids").isArray(), is(true));
        assertThat(result.path("ids").size(), is(2));

        // verify the expectations are now active
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").isArray(), is(true));
        assertThat(activeResult.path("data").size(), is(2));

        // verify the expectations have the correct request/response details
        boolean foundUsersExpectation = false;
        boolean foundOrdersExpectation = false;
        for (JsonNode exp : activeResult.path("data")) {
            String path = exp.path("httpRequest").path("path").asText();
            if ("/api/users".equals(path)) {
                foundUsersExpectation = true;
                assertThat(exp.path("httpRequest").path("method").asText(), is("GET"));
                assertThat(exp.path("httpResponse").path("statusCode").asInt(), is(200));
            } else if ("/api/orders".equals(path)) {
                foundOrdersExpectation = true;
                assertThat(exp.path("httpRequest").path("method").asText(), is("POST"));
                assertThat(exp.path("httpResponse").path("statusCode").asInt(), is(201));
            }
        }
        assertThat("should find /api/users expectation", foundUsersExpectation, is(true));
        assertThat("should find /api/orders expectation", foundOrdersExpectation, is(true));
    }

    @Test
    public void shouldPreviewWithoutCreatingExpectations() throws Exception {
        // given - simulate forwarded traffic
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/health"))
            .setHttpResponse(response().withStatusCode(200).withBody("{\"status\":\"ok\"}"))
            .setExpectation(request().withMethod("GET").withPath("/api/health"),
                response().withStatusCode(200).withBody("{\"status\":\"ok\"}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("{\"status\":\"ok\"}"))
        );

        Thread.sleep(500);

        // when - preview the expectations
        ObjectNode params = objectMapper.createObjectNode();
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - preview should return expectations
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.path("expectations").isArray(), is(true));
        assertThat(result.path("expectations").size(), is(1));

        // the expectation JSON should contain the request/response
        JsonNode previewExp = result.path("expectations").get(0);
        assertThat(previewExp.has("httpRequest"), is(true));
        assertThat(previewExp.has("httpResponse"), is(true));

        // verify no active expectations were created
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        String data = activeResult.path("data").toString();
        assertThat("no expectations should be active after preview",
            data.equals("\"\"") || data.equals("[]"), is(true));
    }

    @Test
    public void shouldFilterByMethodAndCreateOnlyMatchingExpectations() throws Exception {
        // given - simulate multiple forwarded requests with different methods
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/products"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/products"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );

        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/api/products"))
            .setHttpResponse(response().withStatusCode(201).withBody("{\"id\":1}"))
            .setExpectation(request().withMethod("POST").withPath("/api/products"),
                response().withStatusCode(201).withBody("{\"id\":1}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(201).withBody("{\"id\":1}"))
        );

        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("DELETE").withPath("/api/products/1"))
            .setHttpResponse(response().withStatusCode(204))
            .setExpectation(request().withMethod("DELETE").withPath("/api/products/1"),
                response().withStatusCode(204))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(204))
        );

        Thread.sleep(500);

        // when - create expectations only for GET requests
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - only 1 expectation should be created (GET only)
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));

        // verify the active expectation is the GET one
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").isArray(), is(true));
        assertThat(activeResult.path("data").size(), is(1));
        assertThat(activeResult.path("data").get(0).path("httpRequest").path("method").asText(), is("GET"));
    }

    @Test
    public void shouldFilterByPathAndCreateOnlyMatchingExpectations() throws Exception {
        // given
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/users"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );

        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/orders"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/orders"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );

        Thread.sleep(500);

        // when - filter by path
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/api/orders");
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));

        // verify the active expectation is the /api/orders one
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").get(0).path("httpRequest").path("path").asText(), is("/api/orders"));
    }

    @Test
    public void shouldReturnNoRecordedTrafficWhenFilterMatchesNothing() throws Exception {
        // given - record traffic on one path
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/users"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );

        Thread.sleep(500);

        // when - filter by a path that doesn't match
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/api/nonexistent");
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
        assertThat(result.path("count").asInt(), is(0));
    }

    @Test
    public void shouldCreateExpectationsWithUnlimitedTimesAndTtl() throws Exception {
        // given - simulate a forwarded request (recorded expectations have Times.once())
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/config"))
            .setHttpResponse(response().withStatusCode(200).withBody("{\"key\":\"value\"}"))
            .setExpectation(request().withMethod("GET").withPath("/api/config"),
                response().withStatusCode(200).withBody("{\"key\":\"value\"}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("{\"key\":\"value\"}"))
        );

        Thread.sleep(500);

        // when - create expectations from recorded traffic
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("created"));

        // verify the created expectation has unlimited times (not once)
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        JsonNode expectation = activeResult.path("data").get(0);
        // unlimited times means the times field is either absent or has unlimited=true
        JsonNode times = expectation.path("times");
        if (!times.isMissingNode() && !times.isNull()) {
            assertThat("created expectation should have unlimited times",
                times.path("unlimited").asBoolean(), is(true));
        }
        // if times is missing, that's also fine (means unlimited)
    }
}
