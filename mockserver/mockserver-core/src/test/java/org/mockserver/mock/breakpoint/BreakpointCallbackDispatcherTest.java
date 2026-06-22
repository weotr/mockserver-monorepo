package org.mockserver.mock.breakpoint;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.WebSocketMessageSerializer;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WEB_SOCKET_CORRELATION_ID_HEADER_NAME;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the WS-callback breakpoint dispatch path for REQUEST and RESPONSE phases.
 * <p>
 * Uses a real {@link WebSocketClientRegistry} with an {@link EmbeddedChannel} to
 * simulate a connected callback client, then verifies that client replies
 * produce the correct {@link BreakpointDecision} outcomes.
 * <p>
 * This test mutates the global singletons {@link BreakpointCallbackDispatcher},
 * {@link BreakpointMatcherRegistry}, so it must
 * run in the sequential Surefire phase.
 */
public class BreakpointCallbackDispatcherTest {

    private static final String CLIENT_ID = "test-breakpoint-client";

    private Configuration configuration;
    private MockServerLogger logger;
    private WebSocketClientRegistry webSocketClientRegistry;
    private WebSocketMessageSerializer serializer;
    private EmbeddedChannel clientChannel;
    private BreakpointCallbackDispatcher dispatcher;

    @Before
    public void setUp() {
        // Clean all breakpoint singletons FIRST, before creating any test infrastructure,
        // so that stale async callbacks from previous test classes cannot race with new state.
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
        // Reset static config properties in case a prior test class changed them
        org.mockserver.configuration.ConfigurationProperties.breakpointTimeoutMillis(30_000);
        org.mockserver.configuration.ConfigurationProperties.breakpointMaxHeld(50);

        configuration = Configuration.configuration()
            .breakpointTimeoutMillis(30_000L)
            .breakpointMaxHeld(50);
        logger = new MockServerLogger();
        webSocketClientRegistry = new WebSocketClientRegistry(configuration, logger);
        serializer = new WebSocketMessageSerializer(logger);
        dispatcher = BreakpointCallbackDispatcher.getInstance();

        // Register a fake WebSocket client
        clientChannel = new EmbeddedChannel();
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(clientChannel);
        webSocketClientRegistry.registerClient(CLIENT_ID, ctx);

        // Drain the registration confirmation message
        clientChannel.readOutbound();
    }

    @After
    public void tearDown() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
        dispatcher.reset();
        if (clientChannel.isOpen()) {
            clientChannel.close();
        }
    }

    // ---- REQUEST phase ----

    @Test
    public void requestPhase_continue_whenClientRepliesWithSameRequest() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/test");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat("future should be non-null", future, is(notNullValue()));
        assertThat("future should not be done yet", future.isDone(), is(false));

        // Read what was sent to the client and extract the correlation id
        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        assertThat("should have sent a frame to the client", sentFrame, is(notNullValue()));
        String sentJson = sentFrame.text();

        // The client replies with an HttpRequest (continue/modify pattern)
        // Extract the correlationId from the sent message
        Object sentMessage = serializer.deserialize(sentJson);
        assertThat(sentMessage, instanceOf(HttpRequest.class));
        HttpRequest sentRequest = (HttpRequest) sentMessage;
        String correlationId = sentRequest.getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);
        assertThat("correlationId should be present", correlationId, is(notNullValue()));

        // Client replies with the original request (continue)
        HttpRequest replyRequest = originalRequest.clone()
            .withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        String replyJson = serializer.serialize(replyRequest);
        webSocketClientRegistry.receivedTextWebSocketFrame(new TextWebSocketFrame(replyJson));

        // The future should complete with a MODIFY decision (the dispatcher always wraps
        // request replies as MODIFY; the caller treats identical-to-original as continue)
        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedRequest(), is(notNullValue()));
        assertThat(decision.getModifiedRequest().getPath().getValue(), is("/api/test"));
    }

    @Test
    public void requestPhase_modify_whenClientRepliesWithDifferentRequest() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/original");
        originalRequest.withReceivedTimestamp(123456789L);

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));

        // Read sent frame and extract correlationId
        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        HttpRequest sentRequest = (HttpRequest) serializer.deserialize(sentFrame.text());
        String correlationId = sentRequest.getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);

        // Client replies with a modified request
        HttpRequest modifiedRequest = request().withMethod("POST").withPath("/api/modified")
            .withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        webSocketClientRegistry.receivedTextWebSocketFrame(
            new TextWebSocketFrame(serializer.serialize(modifiedRequest)));

        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedRequest().getPath().getValue(), is("/api/modified"));
        assertThat(decision.getModifiedRequest().getMethod().getValue(), is("POST"));
        // The client's echoed/modified request lost the transient receivedTimestamp
        // (it is @JsonIgnore); the dispatcher re-applies the ORIGINAL request's value
        // so the subsequent RESPONSE phase groups by the original request time.
        assertThat(decision.getModifiedRequest().getReceivedTimestamp(), is(123456789L));
    }

    @Test
    public void requestPhase_abort_whenClientRepliesWithResponse() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/abort-me");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));

        // Read sent frame and extract correlationId
        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        HttpRequest sentRequest = (HttpRequest) serializer.deserialize(sentFrame.text());
        String correlationId = sentRequest.getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);

        // Client replies with an HttpResponse (abort)
        HttpResponse abortResponse = response().withStatusCode(403).withReasonPhrase("Forbidden")
            .withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        webSocketClientRegistry.receivedTextWebSocketFrame(
            new TextWebSocketFrame(serializer.serialize(abortResponse)));

        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.ABORT));
        assertThat(decision.getAbortResponse(), is(notNullValue()));
        assertThat(decision.getAbortResponse().getStatusCode(), is(403));
    }

    // ---- RESPONSE phase ----

    @Test
    public void responsePhase_continue_whenClientRepliesWithSameResponse() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/test");
        HttpResponse originalResponse = response().withStatusCode(200).withBody("original body");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchResponse(
            CLIENT_ID, originalRequest, originalResponse, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));
        assertThat(future.isDone(), is(false));

        // Read sent frame — for response phase, sendClientMessage sends request+response
        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        assertThat(sentFrame, is(notNullValue()));
        // The message is an HttpRequestAndHttpResponse — extract correlationId from the request
        Object sentMessage = serializer.deserialize(sentFrame.text());
        assertThat(sentMessage, instanceOf(org.mockserver.model.HttpRequestAndHttpResponse.class));
        org.mockserver.model.HttpRequestAndHttpResponse sentPair = (org.mockserver.model.HttpRequestAndHttpResponse) sentMessage;
        String correlationId = sentPair.getHttpRequest().getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);
        assertThat(correlationId, is(notNullValue()));

        // Client replies with the original response (continue)
        HttpResponse replyResponse = originalResponse.clone()
            .withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        webSocketClientRegistry.receivedTextWebSocketFrame(
            new TextWebSocketFrame(serializer.serialize(replyResponse)));

        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedResponse(), is(notNullValue()));
        assertThat(decision.getModifiedResponse().getStatusCode(), is(200));
    }

    @Test
    public void responsePhase_modify_whenClientRepliesWithDifferentResponse() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/test");
        HttpResponse originalResponse = response().withStatusCode(200).withBody("original");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchResponse(
            CLIENT_ID, originalRequest, originalResponse, webSocketClientRegistry, configuration, logger
        );

        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        Object sentMessage = serializer.deserialize(sentFrame.text());
        org.mockserver.model.HttpRequestAndHttpResponse sentPair = (org.mockserver.model.HttpRequestAndHttpResponse) sentMessage;
        String correlationId = sentPair.getHttpRequest().getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);

        // Client replies with a modified response
        HttpResponse modifiedResponse = response().withStatusCode(201).withBody("modified body")
            .withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        webSocketClientRegistry.receivedTextWebSocketFrame(
            new TextWebSocketFrame(serializer.serialize(modifiedResponse)));

        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedResponse().getStatusCode(), is(201));
        assertThat(decision.getModifiedResponse().getBodyAsString(), is("modified body"));
    }

    // ---- Timeout ----

    @Test
    public void shouldAutoContinueOnTimeout() throws Exception {
        // Use a short timeout
        Configuration shortTimeoutConfig = Configuration.configuration();
        shortTimeoutConfig.breakpointTimeoutMillis(200L);

        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/timeout");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, shortTimeoutConfig, logger
        );
        assertThat(future, is(notNullValue()));

        // Don't send any reply — let it timeout
        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    // ---- Client not connected ----

    @Test
    public void shouldReturnNullWhenClientNotConnected() {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/test");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            "nonexistent-client", originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat("should return null when client not connected", future, is(nullValue()));
    }

    // ---- clientId absent falls back to REST-park ----

    @Test
    public void shouldNotDispatchOverWsWhenClientIdIsNull() {
        // Register a breakpoint without a clientId — this should NOT use the dispatcher
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();
        String id = registry.register(
            request().withPath("/api/test"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        BreakpointMatcher match = registry.findMatch(
            request().withPath("/api/test"),
            BreakpointPhase.REQUEST
        );
        assertThat(match, is(notNullValue()));
        assertThat("clientId should be null for REST-park breakpoints", match.getClientId(), is(nullValue()));
    }

    @Test
    public void shouldDispatchOverWsWhenClientIdIsSet() {
        // Register a breakpoint with a clientId
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();
        String id = registry.register(
            request().withPath("/api/test"),
            EnumSet.of(BreakpointPhase.REQUEST),
            CLIENT_ID,
            configuration, logger
        );

        BreakpointMatcher match = registry.findMatch(
            request().withPath("/api/test"),
            BreakpointPhase.REQUEST
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getClientId(), is(CLIENT_ID));
    }

    // ---- Disconnect cleanup ----

    @Test
    public void disconnectShouldRemoveClientBreakpoints() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // Register breakpoints for two clients
        registry.register(request().withPath("/a"), EnumSet.of(BreakpointPhase.REQUEST),
            CLIENT_ID, configuration, logger);
        registry.register(request().withPath("/b"), EnumSet.of(BreakpointPhase.RESPONSE),
            CLIENT_ID, configuration, logger);
        registry.register(request().withPath("/c"), EnumSet.of(BreakpointPhase.REQUEST),
            "other-client", configuration, logger);
        // Also one without clientId (REST-park)
        registry.register(request().withPath("/d"), EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger);

        assertThat(registry.size(), is(4));

        // Simulate disconnect
        int removed = registry.removeByClientId(CLIENT_ID);
        assertThat(removed, is(2));
        assertThat(registry.size(), is(2));

        // The other client's breakpoint and the REST-park breakpoint should remain
        assertThat(registry.findMatch(request().withPath("/c"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/d"), BreakpointPhase.REQUEST), is(notNullValue()));
        // The disconnected client's breakpoints should be gone
        assertThat(registry.findMatch(request().withPath("/a"), BreakpointPhase.REQUEST), is(nullValue()));
        assertThat(registry.findMatch(request().withPath("/b"), BreakpointPhase.RESPONSE), is(nullValue()));
    }

    @Test
    public void disconnectShouldAutoContinueInFlightDispatches() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/inflight");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));
        assertThat(future.isDone(), is(false));
        assertThat(dispatcher.inFlightCount(), is(1));

        // Simulate disconnect
        int autoContinued = dispatcher.autoCompleteForClient(CLIENT_ID);
        assertThat(autoContinued, is(1));

        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void unregisterClientShouldCleanUpBreakpointsAndInFlight() throws Exception {
        // Register a breakpoint with clientId
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();
        registry.register(request().withPath("/api/cleanup"), EnumSet.of(BreakpointPhase.REQUEST),
            CLIENT_ID, configuration, logger);
        assertThat(registry.size(), is(1));

        // Dispatch a breakpoint (creates in-flight)
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/cleanup");
        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));
        assertThat(future.isDone(), is(false));

        // Simulate WS disconnect via WebSocketClientRegistry.unregisterClient
        // (which calls removeByClientId + autoCompleteForClient)
        webSocketClientRegistry.unregisterClient(CLIENT_ID);

        // Breakpoint matchers should be removed
        assertThat(registry.size(), is(0));

        // In-flight dispatch should be auto-completed
        BreakpointDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    // ---- Request timestamp header ----

    @Test
    public void requestPhase_shouldIncludeRequestTimestampHeader() throws Exception {
        long timestamp = 1717000000000L;
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/timestamp");
        originalRequest.withReceivedTimestamp(timestamp);

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, "bp-ts-1", originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));

        // Read the dispatched frame and verify the timestamp header
        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        HttpRequest sentRequest = (HttpRequest) serializer.deserialize(sentFrame.text());
        String tsHeader = sentRequest.getFirstHeader("X-MockServer-RequestTimestamp");
        assertThat("X-MockServer-RequestTimestamp must be present", tsHeader, is(notNullValue()));
        assertThat("X-MockServer-RequestTimestamp must match the request's receivedTimestamp",
            tsHeader, is(String.valueOf(timestamp)));

        // Clean up
        future.complete(BreakpointDecision.continueOriginal());
    }

    @Test
    public void responsePhase_shouldIncludeRequestTimestampHeader() throws Exception {
        long timestamp = 1717000000123L;
        HttpRequest originalRequest = request().withMethod("POST").withPath("/api/resp-ts");
        originalRequest.withReceivedTimestamp(timestamp);
        HttpResponse originalResponse = response().withStatusCode(200).withBody("ok");

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchResponse(
            CLIENT_ID, "bp-ts-2", originalRequest, originalResponse, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));

        // Read the dispatched frame and verify the timestamp header
        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        Object sentMessage = serializer.deserialize(sentFrame.text());
        assertThat(sentMessage, instanceOf(org.mockserver.model.HttpRequestAndHttpResponse.class));
        org.mockserver.model.HttpRequestAndHttpResponse sentPair = (org.mockserver.model.HttpRequestAndHttpResponse) sentMessage;
        String tsHeader = sentPair.getHttpRequest().getFirstHeader("X-MockServer-RequestTimestamp");
        assertThat("X-MockServer-RequestTimestamp must be present on RESPONSE dispatch", tsHeader, is(notNullValue()));
        assertThat("X-MockServer-RequestTimestamp must match the request's receivedTimestamp",
            tsHeader, is(String.valueOf(timestamp)));

        // Clean up
        future.complete(BreakpointDecision.continueOriginal());
    }

    @Test
    public void requestPhase_shouldNotIncludeTimestampHeaderWhenNull() throws Exception {
        HttpRequest originalRequest = request().withMethod("GET").withPath("/api/no-ts");
        // receivedTimestamp is null by default

        CompletableFuture<BreakpointDecision> future = dispatcher.dispatchRequest(
            CLIENT_ID, originalRequest, webSocketClientRegistry, configuration, logger
        );
        assertThat(future, is(notNullValue()));

        TextWebSocketFrame sentFrame = clientChannel.readOutbound();
        HttpRequest sentRequest = (HttpRequest) serializer.deserialize(sentFrame.text());
        String tsHeader = sentRequest.getFirstHeader("X-MockServer-RequestTimestamp");
        assertThat("X-MockServer-RequestTimestamp should be absent when receivedTimestamp is null",
            tsHeader, is(""));

        // Clean up
        future.complete(BreakpointDecision.continueOriginal());
    }

    // ---- Max-held cap ----

    @Test
    public void shouldReturnNullWhenMaxHeldCapReached() {
        Configuration lowCapConfig = Configuration.configuration();
        lowCapConfig.breakpointMaxHeld(1);

        HttpRequest request1 = request().withMethod("GET").withPath("/api/first");
        HttpRequest request2 = request().withMethod("GET").withPath("/api/second");

        // First dispatch should succeed
        CompletableFuture<BreakpointDecision> future1 = dispatcher.dispatchRequest(
            CLIENT_ID, request1, webSocketClientRegistry, lowCapConfig, logger
        );
        assertThat(future1, is(notNullValue()));

        // Second dispatch should be rejected (cap reached)
        CompletableFuture<BreakpointDecision> future2 = dispatcher.dispatchRequest(
            CLIENT_ID, request2, webSocketClientRegistry, lowCapConfig, logger
        );
        assertThat("should return null when cap is reached", future2, is(nullValue()));

        // Clean up
        future1.complete(BreakpointDecision.continueOriginal());
    }
}
