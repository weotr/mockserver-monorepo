package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Integration test for the full LLM record/replay round trip:
 * 1. Produce FORWARDED_REQUEST log entries (simulating proxy recording)
 * 2. Call record_llm_fixtures to write a fixture file
 * 3. Call load_expectations_from_file to load the fixture
 * 4. Verify the expectations are active and secrets are redacted
 */
public class RecordLlmFixturesIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

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
    public void shouldRecordAndReplayNonStreamingTraffic() throws Exception {
        // given -- simulate forwarded API traffic with a secret header
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/chat/completions")
                .withHeader("Authorization", "Bearer sk-secret-key-12345")
                .withHeader("Content-Type", "application/json"))
            .setHttpResponse(response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":\"Hello!\"}}]}"))
            .setExpectation(
                request().withMethod("POST").withPath("/v1/chat/completions")
                    .withHeader("Authorization", "Bearer sk-secret-key-12345")
                    .withHeader("Content-Type", "application/json"),
                response().withStatusCode(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":\"Hello!\"}}]}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when -- record to fixture file
        File fixtureFile = new File(tempFolder.getRoot(), "llm-fixture.json");
        ObjectNode recordParams = objectMapper.createObjectNode();
        recordParams.put("path", fixtureFile.getAbsolutePath());
        JsonNode recordResult = toolRegistry.callTool("record_llm_fixtures", recordParams);

        // then -- fixture file written
        assertThat(recordResult.path("status").asText(), is("written"));
        assertThat(recordResult.path("count").asInt(), is(1));
        assertThat(fixtureFile.exists(), is(true));

        // verify secrets are redacted in the file
        String fixtureContent = new String(Files.readAllBytes(fixtureFile.toPath()), StandardCharsets.UTF_8);
        assertThat("Authorization should be redacted", fixtureContent, containsString("***REDACTED***"));
        assertThat("Secret key should NOT appear", fixtureContent, not(containsString("sk-secret-key-12345")));

        // when -- reset and load the fixture
        httpState.reset();
        Thread.sleep(200);

        ObjectNode loadParams = objectMapper.createObjectNode();
        loadParams.put("path", fixtureFile.getAbsolutePath());
        JsonNode loadResult = toolRegistry.callTool("load_expectations_from_file", loadParams);

        // then -- expectations loaded
        assertThat(loadResult.path("status").asText(), is("loaded"));
        assertThat(loadResult.path("count").asInt(), is(1));

        // verify the expectations are active
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").isArray(), is(true));
        assertThat(activeResult.path("data").size(), is(1));
    }

    @Test
    public void shouldRecordAndReplaySseStreamingTraffic() throws Exception {
        // given -- simulate forwarded SSE streaming traffic (like Anthropic Claude)
        String sseBody =
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_01\",\"model\":\"claude-3-opus\"}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\" world!\"}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n";

        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages")
                .withHeader("x-api-key", "ant-api-key-secret-456")
                .withHeader("Content-Type", "application/json"))
            .setHttpResponse(response().withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("X-Request-Id", "req-abc")
                .withBody(sseBody))
            .setExpectation(
                request().withMethod("POST").withPath("/v1/messages")
                    .withHeader("x-api-key", "ant-api-key-secret-456")
                    .withHeader("Content-Type", "application/json"),
                response().withStatusCode(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withHeader("x-mockserver-streamed", "true")
                    .withHeader("X-Request-Id", "req-abc")
                    .withBody(sseBody))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when -- record to fixture file
        File fixtureFile = new File(tempFolder.getRoot(), "sse-fixture.json");
        ObjectNode recordParams = objectMapper.createObjectNode();
        recordParams.put("path", fixtureFile.getAbsolutePath());
        JsonNode recordResult = toolRegistry.callTool("record_llm_fixtures", recordParams);

        // then -- fixture file written
        assertThat(recordResult.path("status").asText(), is("written"));
        assertThat(recordResult.path("count").asInt(), is(1));

        // verify the fixture content
        String fixtureContent = new String(Files.readAllBytes(fixtureFile.toPath()), StandardCharsets.UTF_8);

        // secrets should be redacted
        assertThat("API key should be redacted", fixtureContent, containsString("***REDACTED***"));
        assertThat("Secret key should NOT appear", fixtureContent, not(containsString("ant-api-key-secret-456")));

        // should contain SSE response structure (httpSseResponse with events)
        assertThat("Should have SSE response", fixtureContent, containsString("httpSseResponse"));
        assertThat("Should have SSE events", fixtureContent, containsString("message_start"));
        assertThat("Should have SSE events", fixtureContent, containsString("content_block_delta"));
        assertThat("Should have SSE events", fixtureContent, containsString("message_stop"));

        // when -- reset and load the fixture
        httpState.reset();
        Thread.sleep(200);

        ObjectNode loadParams = objectMapper.createObjectNode();
        loadParams.put("path", fixtureFile.getAbsolutePath());
        JsonNode loadResult = toolRegistry.callTool("load_expectations_from_file", loadParams);

        // then -- expectations loaded (SSE response)
        assertThat(loadResult.path("status").asText(), is("loaded"));
        assertThat(loadResult.path("count").asInt(), is(1));

        // verify the active expectation has SSE response structure
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").isArray(), is(true));
        assertThat(activeResult.path("data").size(), is(1));
        JsonNode expectation = activeResult.path("data").get(0);
        // SSE expectations should have httpSseResponse, not httpResponse
        assertThat("Should have SSE response action", expectation.has("httpSseResponse"), is(true));
        assertThat("SSE response should have events",
            expectation.path("httpSseResponse").path("events").isArray(), is(true));
    }

    @Test
    public void shouldFilterByRequestPath() throws Exception {
        // given -- simulate two different API calls
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody("{\"reply\":\"hi\"}"))
            .setExpectation(
                request().withMethod("POST").withPath("/v1/messages"),
                response().withStatusCode(200).withBody("{\"reply\":\"hi\"}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/v1/models"))
            .setHttpResponse(response().withStatusCode(200).withBody("{\"models\":[]}"))
            .setExpectation(
                request().withMethod("GET").withPath("/v1/models"),
                response().withStatusCode(200).withBody("{\"models\":[]}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when -- record only /v1/messages traffic
        File fixtureFile = new File(tempFolder.getRoot(), "filtered-fixture.json");
        ObjectNode recordParams = objectMapper.createObjectNode();
        recordParams.put("path", fixtureFile.getAbsolutePath());
        recordParams.put("requestPath", "/v1/messages");
        JsonNode recordResult = toolRegistry.callTool("record_llm_fixtures", recordParams);

        // then -- only 1 expectation written
        assertThat(recordResult.path("status").asText(), is("written"));
        assertThat(recordResult.path("count").asInt(), is(1));
    }

    @Test
    public void shouldHandleTruncatedSseGracefully() throws Exception {
        // given -- simulate a truncated SSE capture
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-stream-truncated", "true")
                .withBody("event: partial\ndata: {\"incomplete\":"))
            .setExpectation(
                request().withMethod("POST").withPath("/v1/messages"),
                response().withStatusCode(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withHeader("x-mockserver-streamed", "true")
                    .withHeader("x-mockserver-stream-truncated", "true")
                    .withBody("event: partial\ndata: {\"incomplete\":"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200))
        );

        Thread.sleep(500);

        // when -- record to fixture file
        File fixtureFile = new File(tempFolder.getRoot(), "truncated-fixture.json");
        ObjectNode recordParams = objectMapper.createObjectNode();
        recordParams.put("path", fixtureFile.getAbsolutePath());
        JsonNode recordResult = toolRegistry.callTool("record_llm_fixtures", recordParams);

        // then -- should write, but as a static response with warning
        assertThat(recordResult.path("status").asText(), is("written"));
        assertThat(recordResult.path("count").asInt(), is(1));

        String fixtureContent = new String(Files.readAllBytes(fixtureFile.toPath()), StandardCharsets.UTF_8);
        // Should have a warning about truncation, and be a static response (httpResponse, not httpSseResponse)
        assertThat("Should contain truncation warning", fixtureContent, containsString("truncated"));
        assertThat("Should be a static response", fixtureContent, containsString("httpResponse"));
    }
}
