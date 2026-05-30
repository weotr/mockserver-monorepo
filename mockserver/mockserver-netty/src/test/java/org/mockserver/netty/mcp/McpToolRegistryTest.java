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
import java.util.Map;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class McpToolRegistryTest {

    private McpToolRegistry toolRegistry;
    private HttpState httpState;
    private LifeCycle server;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);

        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    @Test
    public void shouldRegisterAllTools() {
        Map<String, McpToolRegistry.ToolDefinition> tools = toolRegistry.getTools();
        assertThat(tools.containsKey("create_expectation"), is(true));
        assertThat(tools.containsKey("verify_request"), is(true));
        assertThat(tools.containsKey("retrieve_recorded_requests"), is(true));
        assertThat(tools.containsKey("clear_expectations"), is(true));
        assertThat(tools.containsKey("reset"), is(true));
        assertThat(tools.containsKey("get_status"), is(true));
        assertThat(tools.containsKey("verify_request_sequence"), is(true));
        assertThat(tools.containsKey("retrieve_request_responses"), is(true));
        assertThat(tools.containsKey("create_forward_expectation"), is(true));
        assertThat(tools.containsKey("debug_request_mismatch"), is(true));
        assertThat(tools.containsKey("create_expectation_from_openapi"), is(true));
        assertThat(tools.containsKey("stop_server"), is(true));
        assertThat(tools.containsKey("raw_expectation"), is(true));
        assertThat(tools.containsKey("raw_retrieve"), is(true));
        assertThat(tools.containsKey("raw_verify"), is(true));
        assertThat(tools.containsKey("explain_unmatched_requests"), is(true));
        assertThat(tools.containsKey("create_expectations_from_recorded_traffic"), is(true));
        assertThat(tools.containsKey("verify_traffic_against_openapi"), is(true));
        assertThat(tools.containsKey("run_contract_test"), is(true));
        assertThat(tools.containsKey("run_resiliency_test"), is(true));
        assertThat(tools.containsKey("record_llm_fixtures"), is(true));
        assertThat(tools.containsKey("load_expectations_from_file"), is(true));
        assertThat(tools.containsKey("mock_llm_completion"), is(true));
        assertThat(tools.containsKey("create_llm_conversation"), is(true));
        assertThat(tools.containsKey("verify_tool_call"), is(true));
        assertThat(tools.containsKey("explain_agent_run"), is(true));
        assertThat(tools.containsKey("detect_llm_drift"), is(true));
        assertThat(tools.containsKey("mock_adversarial_llm_response"), is(true));
        assertThat(tools.containsKey("run_mcp_contract_test"), is(true));
        assertThat(tools.containsKey("verify_structured_output"), is(true));
        assertThat(tools.size(), is(30));
    }

    @Test
    public void shouldHaveToolDefinitionsWithSchemas() {
        for (McpToolRegistry.ToolDefinition tool : toolRegistry.getTools().values()) {
            assertThat(tool.getName(), notNullValue());
            assertThat(tool.getDescription(), notNullValue());
            assertThat(tool.getInputSchema(), notNullValue());
            assertThat(tool.getInputSchema().path("type").asText(), is("object"));
        }
    }

    @Test
    public void shouldCreateExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/hello");
        params.put("statusCode", 200);
        params.put("responseBody", "world");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.has("id"), is(true));
    }

    @Test
    public void shouldCreateExpectationWithTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/limited");
        params.put("statusCode", 200);
        params.put("times", 5);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectNonNumericTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/invalid-times");
        params.put("statusCode", 200);
        params.put("times", "not-a-number");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'times' must be an integer"));
    }

    @Test
    public void shouldVerifyRequestNotFound() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/not-called");
        params.put("atLeast", 1);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("verified").asBoolean(), is(false));
    }

    @Test
    public void shouldVerifyZeroRequests() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/not-called");
        params.put("atLeast", 0);
        params.put("atMost", 0);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("verified").asBoolean(), is(true));
    }

    @Test
    public void shouldRetrieveEmptyRequests() {
        ObjectNode params = objectMapper.createObjectNode();

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("requests").isArray(), is(true));
    }

    @Test
    public void shouldClearExpectations() {
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "GET");
        createParams.put("path", "/to-clear");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        ObjectNode clearParams = objectMapper.createObjectNode();
        clearParams.put("type", "ALL");

        JsonNode result = toolRegistry.callTool("clear_expectations", clearParams);
        assertThat(result.path("status").asText(), is("cleared"));
    }

    @Test
    public void shouldReset() {
        JsonNode result = toolRegistry.callTool("reset", objectMapper.createObjectNode());
        assertThat(result.path("status").asText(), is("reset"));
    }

    @Test
    public void shouldGetStatus() {
        JsonNode result = toolRegistry.callTool("get_status", objectMapper.createObjectNode());
        assertThat(result.path("running").asBoolean(), is(true));
        assertThat(result.path("ports").get(0).asInt(), is(1080));
    }

    @Test
    public void shouldVerifyRequestSequence() {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("requests");

        JsonNode result = toolRegistry.callTool("verify_request_sequence", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void shouldCreateForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", 8080);
        params.put("scheme", "HTTP");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("forwardHost").asText(), is("example.com"));
    }

    @Test
    public void shouldDebugRequestMismatchWithNoExpectations() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");

        JsonNode result = toolRegistry.callTool("debug_request_mismatch", params);
        assertThat(result.path("totalExpectations").asInt(), is(0));
        assertThat(result.path("results").isArray(), is(true));
    }

    @Test
    public void shouldDebugRequestMismatchWithExpectation() {
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "POST");
        createParams.put("path", "/expected");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        ObjectNode debugParams = objectMapper.createObjectNode();
        debugParams.put("method", "GET");
        debugParams.put("path", "/wrong");

        JsonNode result = toolRegistry.callTool("debug_request_mismatch", debugParams);
        assertThat(result.path("totalExpectations").asInt(), is(1));
        assertThat(result.path("results").get(0).path("matches").asBoolean(), is(false));
        assertThat(result.path("results").get(0).has("differences"), is(true));
    }

    @Test
    public void shouldReturnNullForUnknownTool() {
        JsonNode result = toolRegistry.callTool("unknown_tool", objectMapper.createObjectNode());
        assertThat(result == null, is(true));
    }

    @Test
    public void shouldHandleRawRetrieve() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "ACTIVE_EXPECTATIONS");
        params.put("format", "JSON");

        JsonNode result = toolRegistry.callTool("raw_retrieve", params);
        assertThat(result.has("data"), is(true));
    }

    @Test
    public void shouldRetrieveRequestResponses() {
        ObjectNode params = objectMapper.createObjectNode();

        JsonNode result = toolRegistry.callTool("retrieve_request_responses", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("requestResponses").isArray(), is(true));
    }

    @Test
    public void shouldHandleRetrieveRecordedRequestsWithFilter() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/filtered");
        params.put("limit", 10);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("total").asInt(), is(0));
    }

    @Test
    public void shouldRejectNegativeLimit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", -1);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectZeroLimit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 0);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectLimitAboveMaximum() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 501);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldAcceptLimitAtMaximum() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 500);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("requests").isArray(), is(true));
    }

    @Test
    public void shouldRejectNegativeLimitForRequestResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", -5);

        JsonNode result = toolRegistry.callTool("retrieve_request_responses", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectLimitAboveMaximumForRequestResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 501);

        JsonNode result = toolRegistry.callTool("retrieve_request_responses", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectInvalidStatusCodeTooLow() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/bad-status");
        params.put("statusCode", 99);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'statusCode' must be between 100 and 999"));
    }

    @Test
    public void shouldRejectInvalidStatusCodeTooHigh() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/bad-status");
        params.put("statusCode", 1000);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'statusCode' must be between 100 and 999"));
    }

    @Test
    public void shouldAcceptValidStatusCodeBoundary() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/good-status-100");
        params.put("statusCode", 100);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));

        ObjectNode params2 = objectMapper.createObjectNode();
        params2.put("method", "GET");
        params2.put("path", "/good-status-999");
        params2.put("statusCode", 999);

        JsonNode result2 = toolRegistry.callTool("create_expectation", params2);
        assertThat(result2.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectMalformedTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-test");
        params.put("timeToLive", "INVALID");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' must be in format '<number> <UNIT>' (e.g., '60 SECONDS')"));
    }

    @Test
    public void shouldRejectFloatTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/float-times");
        params.put("statusCode", 200);
        params.put("times", 3.5);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'times' must be an integer"));
    }

    @Test
    public void shouldHandleClearWithFilter() {
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "GET");
        createParams.put("path", "/keep");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        ObjectNode createParams2 = objectMapper.createObjectNode();
        createParams2.put("method", "POST");
        createParams2.put("path", "/remove");
        createParams2.put("statusCode", 201);
        toolRegistry.callTool("create_expectation", createParams2);

        ObjectNode clearParams = objectMapper.createObjectNode();
        clearParams.put("method", "POST");
        clearParams.put("path", "/remove");
        clearParams.put("type", "EXPECTATIONS");

        JsonNode result = toolRegistry.callTool("clear_expectations", clearParams);
        assertThat(result.path("status").asText(), is("cleared"));
    }

    @Test
    public void shouldRejectBlankMethod() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "");
        params.put("path", "/test");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'method' is required and must not be blank"));
    }

    @Test
    public void shouldRejectMissingMethod() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/test");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'method' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankPath() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "   ");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectMissingPath() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectNegativeTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/negative-times");
        params.put("statusCode", 200);
        params.put("times", -1);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'times' must be a non-negative integer"));
    }

    @Test
    public void shouldAcceptZeroTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/zero-times");
        params.put("statusCode", 200);
        params.put("times", 0);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectNonNumericTimeToLiveValue() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-nan");
        params.put("timeToLive", "abc SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' value must be a number"));
    }

    @Test
    public void shouldRejectNegativeTimeToLiveValue() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-negative");
        params.put("timeToLive", "-5 SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' value must be positive"));
    }

    @Test
    public void shouldRejectZeroTimeToLiveValue() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-zero");
        params.put("timeToLive", "0 SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' value must be positive"));
    }

    @Test
    public void shouldRejectInvalidTimeToLiveUnit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-bad-unit");
        params.put("timeToLive", "60 FORTNIGHTS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'timeToLive' unit must be one of:"));
    }

    @Test
    public void shouldAcceptValidTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-valid");
        params.put("timeToLive", "60 SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectStringStatusCode() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/string-status");
        params.put("statusCode", "abc");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'statusCode' must be an integer"));
    }

    @Test
    public void shouldNotExposeExceptionDetailsInErrorMessages() {
        // raw_expectation with invalid JSON should return a generic error, not expose internals
        ObjectNode params = objectMapper.createObjectNode();
        params.put("expectation", "not valid json");

        JsonNode result = toolRegistry.callTool("raw_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        String message = result.path("message").asText();
        assertThat(message, is("Failed to create raw expectation"));
        // Should NOT contain any Java exception class names or stack trace details
        assertThat(message, not(containsString("Exception")));
        assertThat(message, not(containsString("at org.")));
    }

    // --- verify_request atLeast/atMost validation ---

    @Test
    public void shouldRejectNonIntegerAtLeast() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atLeast", "abc");

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atLeast' must be an integer"));
    }

    @Test
    public void shouldRejectNonIntegerAtMost() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atMost", 3.5);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atMost' must be an integer"));
    }

    @Test
    public void shouldRejectNegativeAtLeast() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atLeast", -1);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atLeast' must be non-negative"));
    }

    @Test
    public void shouldRejectAtMostLessThanAtLeast() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atLeast", 5);
        params.put("atMost", 2);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atMost' must be >= 'atLeast'"));
    }

    // --- clear_expectations type validation ---

    @Test
    public void shouldRejectInvalidClearType() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "INVALID");

        JsonNode result = toolRegistry.callTool("clear_expectations", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'type' must be one of: ALL, LOG, EXPECTATIONS"));
    }

    @Test
    public void shouldAcceptValidClearTypes() {
        for (String type : new String[]{"ALL", "LOG", "EXPECTATIONS"}) {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("type", type);

            JsonNode result = toolRegistry.callTool("clear_expectations", params);
            assertThat("type " + type + " should be accepted", result.path("status").asText(), is("cleared"));
        }
    }

    // --- create_forward_expectation validation ---

    @Test
    public void shouldRejectBlankPathForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "");
        params.put("host", "example.com");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankHostForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "  ");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'host' is required and must not be blank"));
    }

    @Test
    public void shouldRejectMissingHostForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'host' is required and must not be blank"));
    }

    @Test
    public void shouldRejectNonIntegerPortForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", "abc");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'port' must be an integer"));
    }

    @Test
    public void shouldRejectPortZeroForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", 0);

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'port' must be between 1 and 65535"));
    }

    @Test
    public void shouldRejectPortAboveMax() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", 65536);

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'port' must be between 1 and 65535"));
    }

    @Test
    public void shouldAcceptValidPortBoundaries() {
        ObjectNode params1 = objectMapper.createObjectNode();
        params1.put("path", "/proxy1");
        params1.put("host", "example.com");
        params1.put("port", 1);

        JsonNode result1 = toolRegistry.callTool("create_forward_expectation", params1);
        assertThat(result1.path("status").asText(), is("created"));
        assertThat(result1.path("forwardPort").asInt(), is(1));

        ObjectNode params2 = objectMapper.createObjectNode();
        params2.put("path", "/proxy2");
        params2.put("host", "example.com");
        params2.put("port", 65535);

        JsonNode result2 = toolRegistry.callTool("create_forward_expectation", params2);
        assertThat(result2.path("status").asText(), is("created"));
        assertThat(result2.path("forwardPort").asInt(), is(65535));
    }

    @Test
    public void shouldRejectInvalidSchemeForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("scheme", "FTP");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'scheme' must be HTTP or HTTPS"));
    }

    // --- raw_retrieve type/format validation ---

    @Test
    public void shouldRejectInvalidRawRetrieveType() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "INVALID");

        JsonNode result = toolRegistry.callTool("raw_retrieve", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'type' must be one of: REQUESTS, REQUEST_RESPONSES, RECORDED_EXPECTATIONS, ACTIVE_EXPECTATIONS, LOGS"));
    }

    @Test
    public void shouldRejectInvalidRawRetrieveFormat() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "REQUESTS");
        params.put("format", "XML");

        JsonNode result = toolRegistry.callTool("raw_retrieve", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'format' must be one of: JSON, JAVA, LOG_ENTRIES"));
    }

    @Test
    public void shouldAcceptAllValidRawRetrieveTypes() {
        for (String type : new String[]{"REQUESTS", "REQUEST_RESPONSES", "RECORDED_EXPECTATIONS", "ACTIVE_EXPECTATIONS", "LOGS"}) {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("type", type);
            params.put("format", "JSON");

            JsonNode result = toolRegistry.callTool("raw_retrieve", params);
            assertThat("type " + type + " should be accepted", result.has("data"), is(true));
        }
    }

    // --- verify_request_sequence element validation ---

    @Test
    public void shouldRejectNonObjectElementsInRequestSequence() {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("requests").add("not-an-object");

        JsonNode result = toolRegistry.callTool("verify_request_sequence", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("Each element of 'requests' must be an object"));
    }

    // --- create_expectation timeToLive non-textual ---

    @Test
    public void shouldRejectNonTextualTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-int");
        params.put("timeToLive", 60);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' must be a string in format '<number> <UNIT>' (e.g., '60 SECONDS')"));
    }

    @Test
    public void shouldRejectBooleanTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-bool");
        params.put("timeToLive", true);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' must be a string in format '<number> <UNIT>' (e.g., '60 SECONDS')"));
    }

    // --- explain_unmatched_requests tool tests ---

    @Test
    public void shouldExplainUnmatchedRequestsWithNoHistory() {
        // given
        ObjectNode params = objectMapper.createObjectNode();

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("unmatchedRequestCount").asInt(), is(0));
        assertThat(result.path("unmatchedRequests").isArray(), is(true));
    }

    @Test
    public void shouldExplainUnmatchedRequestsWithLimit() {
        // given
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 5);

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.has("unmatchedRequestCount"), is(true));
        assertThat(result.path("unmatchedRequests").isArray(), is(true));
    }

    @Test
    public void shouldRejectInvalidLimitForExplainUnmatched() {
        // given
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 0);

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 100"));
    }

    @Test
    public void shouldRejectLimitAboveMaxForExplainUnmatched() {
        // given
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 101);

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 100"));
    }

    @Test
    public void shouldExplainUnmatchedRequestsToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("explain_unmatched_requests");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("matched no expectation"));
        assertThat(tool.getInputSchema().path("properties").has("limit"), is(true));
    }

    // --- debug_request_mismatch ranking and remediation tests ---

    @Test
    public void shouldDebugRequestMismatchWithRankedResultsAndRemediation() {
        // given - create two expectations with different paths
        ObjectNode createParams1 = objectMapper.createObjectNode();
        createParams1.put("method", "GET");
        createParams1.put("path", "/close-match");
        createParams1.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams1);

        ObjectNode createParams2 = objectMapper.createObjectNode();
        createParams2.put("method", "POST");
        createParams2.put("path", "/far-match");
        createParams2.put("statusCode", 201);
        toolRegistry.callTool("create_expectation", createParams2);

        // when - debug with a request that matches method of first but not path
        ObjectNode debugParams = objectMapper.createObjectNode();
        debugParams.put("method", "GET");
        debugParams.put("path", "/wrong-path");

        JsonNode result = toolRegistry.callTool("debug_request_mismatch", debugParams);

        // then
        assertThat(result.path("totalExpectations").asInt(), is(2));
        assertThat(result.path("results").isArray(), is(true));
        // results should be ranked - first result should have more matched fields
        JsonNode firstResult = result.path("results").get(0);
        JsonNode secondResult = result.path("results").get(1);
        int firstDiffering = firstResult.path("totalFieldCount").asInt() - firstResult.path("matchedFieldCount").asInt();
        int secondDiffering = secondResult.path("totalFieldCount").asInt() - secondResult.path("matchedFieldCount").asInt();
        assertThat("results should be ranked with closest match first", firstDiffering <= secondDiffering, is(true));
    }

    // --- create_expectations_from_recorded_traffic tool tests ---

    @Test
    public void shouldCreateExpectationsFromRecordedTrafficToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("create_expectations_from_recorded_traffic");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("recorded"));
        assertThat(tool.getDescription(), containsString("forwarding"));
        assertThat(tool.getInputSchema().path("properties").has("method"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("preview"), is(true));
    }

    @Test
    public void shouldReturnNoRecordedTrafficWhenNoneExists() {
        // given
        ObjectNode params = objectMapper.createObjectNode();

        // when
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
        assertThat(result.path("count").asInt(), is(0));
    }

    @Test
    public void shouldPreviewRecordedExpectationsWithoutAdding() throws Exception {
        // given - simulate FORWARDED_REQUEST log entries (what proxy mode creates)
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[{\"id\":1}]"))
            .setExpectation(request().withMethod("GET").withPath("/api/users"),
                response().withStatusCode(200).withBody("[{\"id\":1}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[{\"id\":1}]"))
        );

        Thread.sleep(500);

        // when - call with preview=true
        ObjectNode params = objectMapper.createObjectNode();
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt() >= 1, is(true));
        assertThat(result.path("expectations").isArray(), is(true));
        assertThat(result.path("expectations").size() >= 1, is(true));

        // verify no active expectations were added (only the forwarding, not from this tool)
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        // should have no active expectations (the recorded ones were just previewed)
        String data = activeResult.path("data").toString();
        assertThat("no active expectations should be added in preview mode",
            data.equals("\"\"") || data.equals("[]"), is(true));
    }

    @Test
    public void shouldCreateExpectationsFromRecordedTraffic() throws Exception {
        // given - simulate FORWARDED_REQUEST log entries
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

        Thread.sleep(500);

        // when - call with preview=false (default)
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt() >= 1, is(true));
        assertThat(result.path("ids").isArray(), is(true));
        assertThat(result.path("ids").size() >= 1, is(true));

        // verify active expectations now exist
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").isArray(), is(true));
        assertThat(activeResult.path("data").size() >= 1, is(true));
    }

    @Test
    public void shouldFilterRecordedTrafficByMethod() throws Exception {
        // given - simulate two FORWARDED_REQUEST log entries with different methods
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/items"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/items"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("DELETE").withPath("/api/items/1"))
            .setHttpResponse(response().withStatusCode(204))
            .setExpectation(request().withMethod("DELETE").withPath("/api/items/1"),
                response().withStatusCode(204))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(204))
        );

        Thread.sleep(500);

        // when - filter by GET method only
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - should only return GET expectations
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt(), is(1));
    }

    // --- verify_traffic_against_openapi tool tests ---

    @Test
    public void shouldVerifyTrafficAgainstOpenApiToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("verify_traffic_against_openapi");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("recorded by MockServer"));
        assertThat(tool.getInputSchema().path("properties").has("specUrlOrPayload"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("method"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
    }

    @Test
    public void shouldRejectBlankSpecForVerifyTraffic() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "");

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'specUrlOrPayload' is required and must not be blank"));
    }

    @Test
    public void shouldReturnNoTrafficWhenNoneExists() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);
        assertThat(result.path("status").asText(), is("no_traffic"));
        assertThat(result.path("totalPairs").asInt(), is(0));
    }

    // --- run_contract_test tool tests ---

    @Test
    public void shouldRunContractTestToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("run_contract_test");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("example requests"));
        assertThat(tool.getDescription(), containsString("OpenAPI"));
        assertThat(tool.getInputSchema().path("properties").has("specUrlOrPayload"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("baseUrl"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("operationId"), is(true));
    }

    @Test
    public void shouldRejectBlankSpecForContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "");
        params.put("baseUrl", "http://localhost:8080");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'specUrlOrPayload' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankBaseUrlForContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'baseUrl' is required and must not be blank"));
    }

    @Test
    public void shouldRejectInvalidBaseUrlForContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "not a url");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'baseUrl' is not a valid URL"));
    }

    // --- run_resiliency_test tool tests ---

    @Test
    public void shouldRunResiliencyTestToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("run_resiliency_test");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("malformed"));
        assertThat(tool.getDescription(), containsString("boundary"));
        assertThat(tool.getDescription(), containsString("OpenAPI"));
        assertThat(tool.getInputSchema().path("properties").has("specUrlOrPayload"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("baseUrl"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("operationId"), is(true));
    }

    @Test
    public void shouldRejectBlankSpecForResiliencyTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "");
        params.put("baseUrl", "http://localhost:8080");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'specUrlOrPayload' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankBaseUrlForResiliencyTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'baseUrl' is required and must not be blank"));
    }

    @Test
    public void shouldRejectInvalidBaseUrlForResiliencyTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "not a url");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'baseUrl' is not a valid URL"));
    }

    // --- record_llm_fixtures tool tests ---

    @Test
    public void shouldRecordLlmFixturesToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("record_llm_fixtures");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("fixture"));
        assertThat(tool.getDescription(), containsString("redacted"));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("host"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("requestPath"), is(true));
    }

    @Test
    public void shouldRejectBlankPathForRecordLlmFixtures() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "");

        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldReturnNoRecordedTrafficForRecordLlmFixtures() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/test-fixture.json");

        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
        assertThat(result.path("count").asInt(), is(0));
    }

    // --- load_expectations_from_file tool tests ---

    @Test
    public void shouldLoadExpectationsFromFileToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("load_expectations_from_file");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("fixture file"));
        assertThat(tool.getDescription(), containsString("replay"));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
    }

    @Test
    public void shouldRejectBlankPathForLoadExpectationsFromFile() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "");

        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectNonExistentFileForLoad() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/nonexistent-fixture-12345.json");

        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("does not exist"));
    }

    @Test
    public void shouldFilterRecordedTrafficByPath() throws Exception {
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
            .setHttpRequest(request().withMethod("GET").withPath("/api/products"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/products"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );

        Thread.sleep(500);

        // when - filter by path
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/api/users");
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - should only return /api/users expectations
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt(), is(1));
    }

    // --- path traversal rejection tests ---

    @Test
    public void shouldRejectPathTraversalForRecordLlmFixtures() {
        // given - path outside allowed directories
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/etc/passwd");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    @Test
    public void shouldRejectDotDotPathTraversalForRecordLlmFixtures() {
        // given - path with ../ that escapes allowed roots
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/../../etc/shadow");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    @Test
    public void shouldRejectPathTraversalForLoadExpectationsFromFile() {
        // given - path outside allowed directories
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/etc/passwd");

        // when
        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    @Test
    public void shouldRejectDotDotPathTraversalForLoadExpectationsFromFile() {
        // given - path with ../ that escapes allowed roots
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/../../etc/shadow");

        // when
        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    // --- M2: null host validation ---

    @Test
    public void shouldRejectBaseUrlWithNoHostForContractTest() {
        // given - a file:// URI has no host
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "file:///some/path");

        // when
        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must be an absolute HTTP/HTTPS URL with a hostname"));
    }

    @Test
    public void shouldRejectBaseUrlWithNoHostForResiliencyTest() {
        // given - a file:// URI has no host
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "file:///some/path");

        // when
        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must be an absolute HTTP/HTTPS URL with a hostname"));
    }

    @Test
    public void shouldRejectNonHttpSchemeForContractTest() {
        // given - a URL with a host but a non-HTTP scheme
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "ftp://example.com/path");

        // when
        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must use the http or https scheme"));
    }

    @Test
    public void shouldRejectNonHttpSchemeForResiliencyTest() {
        // given - a URL with a host but a non-HTTP scheme
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "ftp://example.com/path");

        // when
        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must use the http or https scheme"));
    }

    // --- M3: file size limit for load_expectations_from_file ---

    @Test
    public void shouldLoadSmallValidFixtureFile() throws Exception {
        // given - create a small valid fixture file
        String tmpDir = System.getProperty("java.io.tmpdir");
        java.nio.file.Path fixturePath = java.nio.file.Paths.get(tmpDir, "mcp-test-small-fixture.json");
        String fixtureContent = "[{\"httpRequest\":{\"method\":\"GET\",\"path\":\"/test\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"ok\"}}]";
        java.nio.file.Files.write(fixturePath, fixtureContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("path", fixturePath.toString());

            // when
            JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);

            // then - should load successfully (not hit size limit)
            assertThat(result.has("error"), is(false));
            assertThat(result.path("status").asText(), is("loaded"));
            assertThat(result.path("count").asInt(), is(1));
        } finally {
            java.nio.file.Files.deleteIfExists(fixturePath);
        }
    }

    @Test
    public void shouldAcceptPathInsideTempDirForRecordLlmFixtures() {
        // given - valid path inside the system temp directory (no recorded traffic, but path should pass validation)
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/valid-fixture.json");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then - should not get path error, should get "no recorded traffic" instead
        assertThat(result.has("error"), is(false));
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
    }

    @Test
    public void shouldAcceptPathInsideWorkingDirForRecordLlmFixtures() {
        // given - valid path inside the working directory (no recorded traffic, but path should pass validation)
        String workingDir = System.getProperty("user.dir");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", workingDir + "/test-output-fixture.json");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then - should not get path error, should get "no recorded traffic" instead
        assertThat(result.has("error"), is(false));
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
    }

    // --- run_mcp_contract_test tool tests ---

    @Test
    public void shouldRunMcpContractTestToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("run_mcp_contract_test");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("MCP"));
        assertThat(tool.getDescription(), containsString("JSON-RPC"));
        assertThat(tool.getInputSchema().path("properties").has("targetUrl"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("protocolVersion"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("toolName"), is(true));
    }

    @Test
    public void shouldRejectBlankTargetUrlForMcpContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "");

        JsonNode result = toolRegistry.callTool("run_mcp_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'targetUrl' is required and must not be blank"));
    }

    @Test
    public void shouldRejectTargetUrlWithNoHostForMcpContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "file:///some/path");

        JsonNode result = toolRegistry.callTool("run_mcp_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must be an absolute HTTP/HTTPS URL with a hostname"));
    }

    @Test
    public void shouldRejectNonHttpSchemeForMcpContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "ftp://example.com/mcp");

        JsonNode result = toolRegistry.callTool("run_mcp_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must use the http or https scheme"));
    }

    // --- verify_structured_output tool tests ---

    @Test
    public void shouldVerifyStructuredOutputToolHasSchema() {
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("verify_structured_output");
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("JSON Schema"));
        assertThat(tool.getInputSchema().path("properties").has("provider"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("schema"), is(true));
    }

    @Test
    public void shouldRejectMissingProviderForVerifyStructuredOutput() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("schema", "{\"type\":\"object\"}");

        JsonNode result = toolRegistry.callTool("verify_structured_output", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'provider' is required"));
    }

    @Test
    public void shouldRejectBlankSchemaForVerifyStructuredOutput() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("schema", "");

        JsonNode result = toolRegistry.callTool("verify_structured_output", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'schema' is required and must not be blank"));
    }

    @Test
    public void shouldReturnZeroCheckedWhenNoRecordedResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("schema", "{\"type\":\"object\",\"required\":[\"city\"]}");

        JsonNode result = toolRegistry.callTool("verify_structured_output", params);
        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(0));
        assertThat(result.path("allConform").asBoolean(), is(false));
    }

    @Test
    public void shouldReportConformingStructuredOutput() throws Exception {
        // Record an Anthropic response whose output text is JSON conforming to the schema.
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"city\\\":\\\"Paris\\\"}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("schema", "{\"type\":\"object\",\"required\":[\"city\"]}");
        JsonNode result = toolRegistry.callTool("verify_structured_output", params);

        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("conforming").asInt(), is(1));
        assertThat(result.path("allConform").asBoolean(), is(true));
    }

    @Test
    public void shouldFlagNonConformingStructuredOutput() throws Exception {
        // Record an Anthropic response whose output text is missing the required field.
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"foo\\\":1}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("schema", "{\"type\":\"object\",\"required\":[\"city\"]}");
        JsonNode result = toolRegistry.callTool("verify_structured_output", params);

        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("nonConforming").asInt(), is(1));
        assertThat(result.path("allConform").asBoolean(), is(false));
    }
}
