package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

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
    public void shouldCreateLlmCompletionWithOutputSchemaString() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("text", "{\"name\":\"Ada\"}");
        params.put("outputSchema", "{\"type\":\"object\",\"required\":[\"name\"]}");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));

        org.mockserver.mock.Expectation expectation = httpState
            .allMatchingExpectation(request().withMethod("POST").withPath("/v1/messages")).get(0);
        assertThat(expectation.getHttpLlmResponse().getCompletion().getOutputSchema(),
            is("{\"type\":\"object\",\"required\":[\"name\"]}"));
    }

    @Test
    public void shouldCreateLlmCompletionWithOutputSchemaObject() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("text", "{\"name\":\"Ada\"}");
        ObjectNode schemaObj = params.putObject("outputSchema");
        schemaObj.put("type", "object");
        schemaObj.putArray("required").add("name");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));

        org.mockserver.mock.Expectation expectation = httpState
            .allMatchingExpectation(request().withMethod("POST").withPath("/v1/messages")).get(0);
        // an inline object is serialized to its JSON-string form
        assertThat(expectation.getHttpLlmResponse().getCompletion().getOutputSchema(),
            containsString("\"type\":\"object\""));
    }

    @Test
    public void shouldRejectNonStringNonObjectOutputSchema() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("text", "{}");
        params.put("outputSchema", 42);

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("outputSchema"));
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

    // --- verify_tool_call / explain_agent_run ---

    private void logToolUseConversation() throws InterruptedException {
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages").withBody("{\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Weather in Paris?\"},\n" +
                "    {\"role\": \"assistant\", \"content\": [{\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"get_weather\", \"input\": {\"city\": \"Paris\"}}]},\n" +
                "    {\"role\": \"user\", \"content\": [{\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": \"sunny\"}]}\n" +
                "  ]\n" +
                "}"))
            .setMessageFormat("received request")
        );
        Thread.sleep(500);
    }

    @Test
    public void shouldVerifyToolCallWasMade() throws InterruptedException {
        logToolUseConversation();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("toolName", "get_weather");

        JsonNode result = toolRegistry.callTool("verify_tool_call", params);
        assertThat(result.path("satisfied").asBoolean(), is(true));
        assertThat(result.path("count").asInt(), is(1));
    }

    @Test
    public void shouldNotVerifyToolCallThatWasNotMade() throws InterruptedException {
        logToolUseConversation();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("toolName", "send_email");

        JsonNode result = toolRegistry.callTool("verify_tool_call", params);
        assertThat(result.path("satisfied").asBoolean(), is(false));
        assertThat(result.path("count").asInt(), is(0));
        assertThat(result.has("message"), is(true));
    }

    @Test
    public void shouldFilterVerifyToolCallByArgumentsRegex() throws InterruptedException {
        logToolUseConversation();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("toolName", "get_weather");
        params.put("argumentsRegex", "London");

        JsonNode result = toolRegistry.callTool("verify_tool_call", params);
        assertThat(result.path("satisfied").asBoolean(), is(false));
    }

    @Test
    public void shouldErrorWhenVerifyToolCallMissingToolName() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        JsonNode result = toolRegistry.callTool("verify_tool_call", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void shouldExplainAgentRun() throws InterruptedException {
        logToolUseConversation();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");

        JsonNode result = toolRegistry.callTool("explain_agent_run", params);
        assertThat(result.path("messageCount").asInt(), is(3));
        assertThat(result.path("assistantTurnCount").asInt(), is(1));
        assertThat(result.path("toolCallSequence").get(0).asText(), is("get_weather"));
        assertThat(result.path("latestMessageRole").asText(), is("TOOL"));
        // call graph is included
        assertThat(result.path("callGraph").path("nodes").size(), is(4));
        assertThat(result.path("callGraph").path("edges").size() >= 3, is(true));
    }

    @Test
    public void shouldExplainAgentRunWithNoRecordedConversation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        JsonNode result = toolRegistry.callTool("explain_agent_run", params);
        assertThat(result.path("messageCount").asInt(), is(0));
        assertThat(result.has("message"), is(true));
    }

    // --- chaos profiles ---

    @Test
    public void shouldCreateLlmCompletionWithChaosProfile() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");
        params.put("text", "ok");
        ObjectNode chaos = params.putObject("chaos");
        chaos.put("errorStatus", 429);
        chaos.put("retryAfter", "30");
        chaos.put("errorProbability", 1.0);

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
    }

    // --- VCR strict mode + replay normalisation ---

    private Path writeFixture(String json) throws Exception {
        Path file = Files.createTempFile("llm-fixture", ".json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void shouldLoadFixturesInStrictMode() throws Exception {
        Path file = writeFixture("[{\"httpRequest\":{\"method\":\"POST\",\"path\":\"/v1/messages\"}," +
            "\"httpResponse\":{\"statusCode\":200,\"body\":\"ok\"}}]");
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("path", file.toString());
            params.put("strict", true);
            JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
            assertThat(result.path("status").asText(), is("loaded"));
            assertThat(result.path("strict").asBoolean(), is(true));
            assertThat(result.path("strictGuards").asInt(), is(1));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void shouldLoadFixturesWithRequestBodyNormalization() throws Exception {
        // body uses mixed-case Request_ID to exercise case-insensitive field dropping
        Path file = writeFixture("[{\"httpRequest\":{\"method\":\"POST\",\"path\":\"/v1/messages\"," +
            "\"body\":{\"type\":\"STRING\",\"string\":\"{\\\"Request_ID\\\":\\\"req_123\\\",\\\"q\\\":\\\"hi\\\"}\"}}," +
            "\"httpResponse\":{\"statusCode\":200,\"body\":\"ok\"}}]");
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("path", file.toString());
            params.putArray("normalizeRequestBodyFields").add("request_id");
            JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
            assertThat(result.path("status").asText(), is("loaded"));
            assertThat(result.path("count").asInt(), is(1));

            // the registered matcher should have dropped the volatile request_id field
            org.mockserver.model.HttpResponse active = httpState.retrieve(org.mockserver.model.HttpRequest.request()
                .withMethod("PUT").withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "ACTIVE_EXPECTATIONS")
                .withQueryStringParameter("format", "JSON"));
            String activeJson = active.getBodyAsString();
            // case-insensitive drop: the mixed-case Request_ID field is gone, q is kept
            assertThat(activeJson.toLowerCase().contains("request_id"), is(false));
            assertThat(activeJson.contains("\"q\""), is(true));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // --- mock_adversarial_llm_response ---

    @Test
    public void shouldMockAdversarialLlmResponse() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("payload", "prompt_injection_ignore_instructions");
        JsonNode result = toolRegistry.callTool("mock_adversarial_llm_response", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.has("id"), is(true));
        assertThat(result.path("category").asText(), is("prompt_injection"));
    }

    @Test
    public void shouldErrorWhenAdversarialPayloadMissing() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        JsonNode result = toolRegistry.callTool("mock_adversarial_llm_response", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void shouldErrorOnUnknownAdversarialPayload() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "OPENAI");
        params.put("path", "/v1/chat/completions");
        params.put("payload", "not_a_real_payload");
        JsonNode result = toolRegistry.callTool("mock_adversarial_llm_response", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    // --- detect_llm_drift (validation wiring; live path covered by DriftDetectorTest) ---

    @Test
    public void shouldErrorWhenDetectDriftMissingCassette() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        JsonNode result = toolRegistry.callTool("detect_llm_drift", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void shouldErrorWhenDetectDriftCassetteFileMissing() throws Exception {
        Path file = Files.createTempFile("missing-cassette", ".json");
        Files.deleteIfExists(file); // ensure it does not exist
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("cassettePath", file.toString());
        JsonNode result = toolRegistry.callTool("detect_llm_drift", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void shouldCreateLlmConversationWithPerTurnChaos() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        ArrayNode turns = params.putArray("turns");
        ObjectNode turn1 = turns.addObject();
        turn1.putObject("response").put("text", "Turn 0");
        ObjectNode turnChaos = turn1.putObject("chaos");
        turnChaos.put("truncateMode", "MID_STREAM");
        turnChaos.put("truncateAtFraction", 0.5);

        JsonNode result = toolRegistry.callTool("create_llm_conversation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
    }
}
