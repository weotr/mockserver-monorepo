package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.llm.IsolationSource;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.client.LlmConversationBuilder.conversation;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.Provider.ANTHROPIC;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Concurrency stress test for LLM conversation mocking with per-session isolation.
 * Registers a single 2-turn conversation isolated by {@code x-session-id} header,
 * then runs 100 concurrent agents each with their own session ID through a
 * complete 2-turn agent loop (tool_use then tool_result). Asserts no state
 * leakage between sessions: every agent must see its own turn-1 then turn-2
 * response in the correct order.
 */
public class LlmConversationConcurrencyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static int mockServerPort;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
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
    public void shouldIsolate100ConcurrentAgentSessions() throws Exception {
        int agentCount = 100;

        // Register a 2-turn conversation isolated by x-session-id
        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .isolateBy(IsolationSource.header("x-session-id"))
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withText("Let me search for that.")
                    .withToolCall(toolUse("search").withArguments("{\"q\":\"weather\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("search")
                .respondingWith(completion()
                    .withText("It is 18C and sunny.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        CyclicBarrier barrier = new CyclicBarrier(agentCount);
        AtomicInteger turn1Errors = new AtomicInteger(0);
        AtomicInteger turn2Errors = new AtomicInteger(0);
        Thread[] threads = new Thread[agentCount];

        for (int i = 0; i < agentCount; i++) {
            final String sessionId = "agent-" + i;
            threads[i] = new Thread(() -> {
                try {
                    // Cap the barrier wait so a single misbehaving thread cannot
                    // stall the entire 100-thread fleet (and thus the CI run).
                    barrier.await(15, TimeUnit.SECONDS);

                    // Turn 1: initial question
                    String turn1Body = "{\"model\":\"claude-sonnet-4-20250514\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather?\"}]}";
                    String turn1Response = sendPostWithHeader(
                        "/v1/messages", turn1Body, "x-session-id", sessionId);

                    if (!turn1Response.contains("200")) {
                        turn1Errors.incrementAndGet();
                        return;
                    }
                    String turn1Json = extractJsonBody(turn1Response);
                    JsonNode turn1 = OBJECT_MAPPER.readTree(turn1Json);
                    if (!"tool_use".equals(turn1.path("stop_reason").asText(""))) {
                        turn1Errors.incrementAndGet();
                        return;
                    }

                    // Turn 2: send tool result
                    String turn2Body = "{\"model\":\"claude-sonnet-4-20250514\",\"messages\":["
                        + "{\"role\":\"user\",\"content\":\"What is the weather?\"},"
                        + "{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"Let me search for that.\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_123\",\"name\":\"search\",\"input\":{\"q\":\"weather\"}}"
                        + "]},"
                        + "{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_123\",\"content\":\"18C and sunny\"}"
                        + "]}"
                        + "]}";
                    String turn2Response = sendPostWithHeader(
                        "/v1/messages", turn2Body, "x-session-id", sessionId);

                    if (!turn2Response.contains("200")) {
                        turn2Errors.incrementAndGet();
                        return;
                    }
                    String turn2Json = extractJsonBody(turn2Response);
                    JsonNode turn2 = OBJECT_MAPPER.readTree(turn2Json);
                    if (!"end_turn".equals(turn2.path("stop_reason").asText(""))) {
                        turn2Errors.incrementAndGet();
                        return;
                    }

                    // Verify the response text is the expected turn 2 content
                    boolean hasExpectedText = false;
                    for (JsonNode block : turn2.path("content")) {
                        if ("text".equals(block.path("type").asText(""))) {
                            if (block.path("text").asText("").contains("18C and sunny")) {
                                hasExpectedText = true;
                            }
                        }
                    }
                    if (!hasExpectedText) {
                        turn2Errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    turn1Errors.incrementAndGet();
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join(30000);
        }
        // Surface hung threads explicitly rather than letting the error-counter
        // assertions report a false green when a thread never reached its checks.
        for (Thread thread : threads) {
            assertThat("thread still alive after join — possible deadlock or barrier timeout",
                thread.isAlive(), is(false));
        }

        assertThat("Turn-1 errors (should be 0)", turn1Errors.get(), is(0));
        assertThat("Turn-2 errors (should be 0)", turn2Errors.get(), is(0));
    }

    private String sendPostWithHeader(String path, String body, String headerName, String headerValue) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServerPort).append("\r\n");
            request.append("Content-Type: application/json\r\n");
            request.append("Connection: close\r\n");
            if (headerName != null && headerValue != null) {
                request.append(headerName).append(": ").append(headerValue).append("\r\n");
            }
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
