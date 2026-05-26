package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Acceptance test covering both ends of the M4 codec rollout for every newly-added
 * provider. Each scenario verifies two independent paths because the public
 * accessor for the running {@code MockServer}'s {@code HttpState} is package-internal:
 * <ol>
 *   <li><b>MCP tool acceptance:</b> {@code toolRegistry.callTool("mock_llm_completion", ...)}
 *       targets a private {@code HttpState} and asserts the tool accepts the provider
 *       (status=created, provider field populated).</li>
 *   <li><b>Codec dispatch:</b> the same expectation is registered on the live
 *       {@link MockServer} via {@link MockServerClient}, an HTTP POST is dispatched, and
 *       the response is parsed and asserted against the provider's wire format.</li>
 * </ol>
 * Together these prove every M4 codec is fully wired through the MCP tool surface and
 * through the action handler. A true single-path MCP-driven dispatch test would require
 * exposing the running server's {@code HttpState}; until that accessor is added this
 * two-phase structure is the closest we can get.
 */
public class McpToolsAllProvidersTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    private static int mockServerPort;
    private static MockServerClient mockServerClient;
    private static McpToolRegistry toolRegistry;
    private static MockServer mockServer;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
        // Create a McpToolRegistry that operates against the live server's HttpState.
        // McpToolRegistry creates expectations in the httpState passed to it.
        // The server's httpState is protected, so we use a separate one via the client.
        // Instead, we create a McpToolRegistry with a mock LifeCycle pointing to a real HttpState.
        // The expectations will be created via the MCP tool and then queried via HTTP.
        // Since we can't access httpState directly, we use a separate registry + state,
        // and then use MockServerClient to forward expectations.
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(mockServerPort));
        when(server.isRunning()).thenReturn(true);
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    @Test
    public void shouldCreateAndDispatchOpenAiResponsesCompletion() throws Exception {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "OPENAI_RESPONSES");
        params.put("path", "/v1/responses");
        params.put("model", "gpt-4o");
        params.put("text", "Hello from Responses API");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("provider").asText(), is("OPENAI_RESPONSES"));

        // Phase 2: codec dispatch. Register the same expectation against the live
        // MockServer so the codec can encode a response when the HTTP request below fires.
        createCompletionViaClient("OPENAI_RESPONSES", "/v1/responses", "gpt-4o", "Hello from Responses API");

        // Send matching HTTP request
        String body = "{\"input\": \"test\", \"model\": \"gpt-4o\"}";
        String response = sendPost("/v1/responses", body);
        assertThat(response, containsString("200"));
        String jsonBody = extractJsonBody(response);
        assertThat(jsonBody.length(), is(greaterThan(0)));
        JsonNode responseNode = OBJECT_MAPPER.readTree(jsonBody);
        assertThat(responseNode.get("object").asText(), is("response"));
        assertThat(responseNode.get("status").asText(), is("completed"));
    }

    @Test
    public void shouldCreateAndDispatchGeminiCompletion() throws Exception {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "GEMINI");
        params.put("path", "/v1beta/models/gemini-2.0-flash:generateContent");
        params.put("model", "gemini-2.0-flash");
        params.put("text", "Hello from Gemini");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("provider").asText(), is("GEMINI"));

        createCompletionViaClient("GEMINI", "/v1beta/models/gemini-2.0-flash:generateContent", "gemini-2.0-flash", "Hello from Gemini");

        String body = "{\"contents\": [{\"role\": \"user\", \"parts\": [{\"text\": \"test\"}]}]}";
        String response = sendPost("/v1beta/models/gemini-2.0-flash:generateContent", body);
        assertThat(response, containsString("200"));
        String jsonBody = extractJsonBody(response);
        JsonNode responseNode = OBJECT_MAPPER.readTree(jsonBody);
        assertThat(responseNode.has("candidates"), is(true));
        assertThat(responseNode.get("candidates").get(0).get("content").get("role").asText(), is("model"));
    }

    @Test
    public void shouldCreateAndDispatchBedrockCompletion() throws Exception {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "BEDROCK");
        params.put("path", "/model/anthropic.claude-3-7-sonnet/invoke");
        params.put("model", "anthropic.claude-3-7-sonnet-20250219-v1:0");
        params.put("text", "Hello from Bedrock");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("provider").asText(), is("BEDROCK"));

        createCompletionViaClient("BEDROCK", "/model/anthropic.claude-3-7-sonnet/invoke", "anthropic.claude-3-7-sonnet-20250219-v1:0", "Hello from Bedrock");

        String body = "{\"messages\": [{\"role\": \"user\", \"content\": \"test\"}]}";
        String response = sendPost("/model/anthropic.claude-3-7-sonnet/invoke", body);
        assertThat(response, containsString("200"));
        String jsonBody = extractJsonBody(response);
        JsonNode responseNode = OBJECT_MAPPER.readTree(jsonBody);
        assertThat(responseNode.get("type").asText(), is("message"));
        assertThat(responseNode.get("role").asText(), is("assistant"));
    }

    @Test
    public void shouldCreateAndDispatchAzureOpenAiCompletion() throws Exception {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "AZURE_OPENAI");
        params.put("path", "/openai/deployments/gpt-4o/chat/completions");
        params.put("model", "gpt-4o");
        params.put("text", "Hello from Azure OpenAI");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("provider").asText(), is("AZURE_OPENAI"));

        createCompletionViaClient("AZURE_OPENAI", "/openai/deployments/gpt-4o/chat/completions", "gpt-4o", "Hello from Azure OpenAI");

        String body = "{\"messages\": [{\"role\": \"user\", \"content\": \"test\"}]}";
        String response = sendPost("/openai/deployments/gpt-4o/chat/completions", body);
        assertThat(response, containsString("200"));
        String jsonBody = extractJsonBody(response);
        JsonNode responseNode = OBJECT_MAPPER.readTree(jsonBody);
        assertThat(responseNode.get("object").asText(), is("chat.completion"));
    }

    @Test
    public void shouldCreateAndDispatchOllamaCompletion() throws Exception {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "OLLAMA");
        params.put("path", "/api/chat");
        params.put("model", "llama3.1");
        params.put("text", "Hello from Ollama");

        JsonNode result = toolRegistry.callTool("mock_llm_completion", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("provider").asText(), is("OLLAMA"));

        createCompletionViaClient("OLLAMA", "/api/chat", "llama3.1", "Hello from Ollama");

        String body = "{\"model\": \"llama3.1\", \"messages\": [{\"role\": \"user\", \"content\": \"test\"}]}";
        String response = sendPost("/api/chat", body);
        assertThat(response, containsString("200"));
        String jsonBody = extractJsonBody(response);
        JsonNode responseNode = OBJECT_MAPPER.readTree(jsonBody);
        assertThat(responseNode.get("model").asText(), is("llama3.1"));
        assertThat(responseNode.get("done").asBoolean(), is(true));
        assertThat(responseNode.get("message").get("role").asText(), is("assistant"));
    }

    /**
     * Creates an LLM completion expectation on the live MockServer via the Java client API.
     * This ensures the expectation is in the live server's HttpState for HTTP dispatch.
     */
    private void createCompletionViaClient(String providerName, String path, String model, String text) {
        org.mockserver.model.Provider provider = org.mockserver.model.Provider.valueOf(providerName);
        mockServerClient.upsert(
            org.mockserver.mock.Expectation.when(
                org.mockserver.model.HttpRequest.request().withMethod("POST").withPath(path)
            ).thenRespondWithLlm(
                org.mockserver.model.HttpLlmResponse.llmResponse()
                    .withProvider(provider)
                    .withModel(model)
                    .withCompletion(
                        org.mockserver.model.Completion.completion()
                            .withText(text)
                            .withStopReason("end_turn")
                    )
            )
        );
    }

    private String sendPost(String path, String body) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServerPort).append("\r\n");
            request.append("Content-Type: application/json\r\n");
            request.append("Connection: close\r\n");
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                output.write(bodyBytes);
            }
            output.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String extractJsonBody(String httpResponse) {
        int bodyStart = httpResponse.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            bodyStart = httpResponse.indexOf("\n\n");
            if (bodyStart < 0) {
                return httpResponse;
            }
            return httpResponse.substring(bodyStart + 2);
        }
        return httpResponse.substring(bodyStart + 4);
    }
}
