package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
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

public class LlmMcpToolsTest {

    private McpToolRegistry toolRegistry;
    private HttpState httpState;
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

    // --- mock_llm_completion ---

    @Test
    public void shouldCreateLlmCompletionExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("model", "claude-sonnet-4");
        params.put("text", "The capital of France is Paris.");
        params.put("stopReason", "end_turn");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.has("id"), is(true));
        assertThat(result.path("provider").asText(), is("ANTHROPIC"));
    }

    @Test
    public void shouldCreateLlmCompletionWithToolCalls() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");
        params.put("text", "Let me search for that.");
        params.put("stopReason", "tool_use");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("name", "search");
        tc.put("arguments", "{\"query\":\"weather\"}");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
    }

    @Test
    public void shouldCreateLlmCompletionWithToolCallsObjectArguments() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("name", "get_weather");
        ObjectNode argsObj = tc.putObject("arguments");
        argsObj.put("city", "Paris");
        argsObj.put("unit", "celsius");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldCreateLlmCompletionWithUsage() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("text", "Hello");
        ObjectNode usage = params.putObject("usage");
        usage.put("inputTokens", 42);
        usage.put("outputTokens", 8);

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectUnknownProvider() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "UNKNOWN_PROVIDER");
        params.put("path", "/v1/messages");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("unsupported LLM provider"));
        assertThat(result.path("message").asText(), containsString("UNKNOWN_PROVIDER"));
    }

    @Test
    public void shouldAcceptGeminiProviderWithCodec() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "GEMINI");
        params.put("path", "/v1/models/gemini/generateContent");
        params.put("text", "Hello from Gemini");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.path("provider").asText(), is("GEMINI"));
    }

    @Test
    public void shouldRejectMissingProvider() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/v1/messages");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'provider' is required"));
    }

    @Test
    public void shouldRejectMissingPath() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'path' is required"));
    }

    @Test
    public void shouldRejectToolCallWithoutName() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("arguments", "{}");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("non-empty 'name'"));
    }

    // --- Gap 3: Malformed toolCall arguments ---

    @Test
    public void shouldAcceptMalformedJsonStringArguments() {
        // Gap 3: toolCalls[0].arguments that is structurally invalid JSON when
        // supplied as a string parameter. Since ToolUse treats arguments as an
        // opaque string, the MCP tool layer accepts it without validation.
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("name", "my_tool");
        tc.put("arguments", "{\"unclosed\":");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        // String arguments are opaque — accepted without JSON validation
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
    }

    @Test
    public void shouldRejectNonStringNonObjectArguments() {
        // Gap 3: arguments that are neither string nor object (e.g., array or number)
        // should be rejected with a clear error
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("name", "my_tool");
        tc.putArray("arguments").add("invalid");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("arguments must be a string or object"));
    }

    @Test
    public void shouldAcceptValidJsonStringArguments() {
        // Confirm valid JSON string arguments are accepted normally
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("name", "my_tool");
        tc.put("arguments", "{\"key\":\"value\"}");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldAcceptPlainTextStringArguments() {
        // Confirm non-JSON string arguments are accepted (opaque passthrough)
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");
        ArrayNode toolCalls = params.putArray("toolCalls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("name", "my_tool");
        tc.put("arguments", "not_a_json_object");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    // --- create_llm_conversation ---

    @Test
    public void shouldCreateLlmConversationWithTwoTurns() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("model", "claude-sonnet-4");

        ArrayNode turns = params.putArray("turns");

        // Turn 0: tool use
        ObjectNode turn0 = turns.addObject();
        ObjectNode match0 = turn0.putObject("match");
        match0.put("turnIndex", 0);
        ObjectNode resp0 = turn0.putObject("response");
        resp0.put("stopReason", "tool_use");
        ArrayNode tc0 = resp0.putArray("toolCalls");
        ObjectNode toolCall = tc0.addObject();
        toolCall.put("name", "search");
        toolCall.put("arguments", "{\"query\":\"test\"}");

        // Turn 1: final answer
        ObjectNode turn1 = turns.addObject();
        ObjectNode match1 = turn1.putObject("match");
        match1.put("containsToolResultFor", "search");
        ObjectNode resp1 = turn1.putObject("response");
        resp1.put("text", "The answer is 42.");
        resp1.put("stopReason", "end_turn");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(2));
        assertThat(result.has("scenarioName"), is(true));
        assertThat(result.path("scenarioName").asText(), containsString("__llm_conv_"));
        assertThat(result.path("states").isArray(), is(true));
        assertThat(result.path("states").size(), is(2));
        assertThat(result.path("states").get(0).path("scenarioState").asText(), is("Started"));
        assertThat(result.path("states").get(0).path("newScenarioState").asText(), is("turn_1"));
        assertThat(result.path("states").get(1).path("scenarioState").asText(), is("turn_1"));
        assertThat(result.path("states").get(1).path("newScenarioState").asText(), is("__done"));
    }

    @Test
    public void shouldCreateLlmConversationWithIsolation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        ObjectNode isolateBy = params.putObject("isolateBy");
        isolateBy.put("source", "header");
        isolateBy.put("name", "x-session-id");

        ArrayNode turns = params.putArray("turns");
        ObjectNode turn0 = turns.addObject();
        ObjectNode resp0 = turn0.putObject("response");
        resp0.put("text", "Hello");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("scenarioName").asText(), containsString("__iso="));
        assertThat(result.path("scenarioName").asText(), containsString("header:x-session-id"));
    }

    @Test
    public void shouldRejectConversationWithUnknownProvider() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "INVALID");
        params.put("path", "/v1/messages");
        ArrayNode turns = params.putArray("turns");
        ObjectNode turn0 = turns.addObject();
        ObjectNode resp0 = turn0.putObject("response");
        resp0.put("text", "Hello");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("unsupported LLM provider"));
    }

    @Test
    public void shouldAcceptGeminiConversationWithCodec() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "GEMINI");
        params.put("path", "/v1/models/gemini/generateContent");
        ArrayNode turns = params.putArray("turns");
        ObjectNode turn0 = turns.addObject();
        ObjectNode resp0 = turn0.putObject("response");
        resp0.put("text", "Hello from Gemini");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
    }

    @Test
    public void shouldRejectConversationWithEmptyTurns() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.putArray("turns");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("non-empty array"));
    }

    @Test
    public void shouldRejectConversationWithInvalidIsolateBySource() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        ObjectNode isolateBy = params.putObject("isolateBy");
        isolateBy.put("source", "body");
        isolateBy.put("name", "field");
        ArrayNode turns = params.putArray("turns");
        ObjectNode turn0 = turns.addObject();
        ObjectNode resp0 = turn0.putObject("response");
        resp0.put("text", "Hello");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("header, queryParameter, cookie"));
    }

    @Test
    public void shouldCreateConversationWithThreeTurns() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");

        ArrayNode turns = params.putArray("turns");

        ObjectNode turn0 = turns.addObject();
        ObjectNode resp0 = turn0.putObject("response");
        resp0.put("text", "Turn 0");

        ObjectNode turn1 = turns.addObject();
        ObjectNode resp1 = turn1.putObject("response");
        resp1.put("text", "Turn 1");

        ObjectNode turn2 = turns.addObject();
        ObjectNode resp2 = turn2.putObject("response");
        resp2.put("text", "Turn 2");

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(3));
        assertThat(result.path("states").size(), is(3));
        assertThat(result.path("states").get(0).path("scenarioState").asText(), is("Started"));
        assertThat(result.path("states").get(0).path("newScenarioState").asText(), is("turn_1"));
        assertThat(result.path("states").get(1).path("scenarioState").asText(), is("turn_1"));
        assertThat(result.path("states").get(1).path("newScenarioState").asText(), is("turn_2"));
        assertThat(result.path("states").get(2).path("scenarioState").asText(), is("turn_2"));
        assertThat(result.path("states").get(2).path("newScenarioState").asText(), is("__done"));
    }
}
