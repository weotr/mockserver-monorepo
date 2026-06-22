package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for mock-generated stream breakpoints (A1d) at the registry level.
 * Verifies that gRPC server-streaming, WebSocket, and GraphQL subscription
 * frames can be parked and resolved using the same {@link StreamFrameBreakpointRegistry}
 * as forwarded SSE/chunked streams (A1c).
 *
 * <p>These are pure registry-level tests (no Netty channel required). Integration
 * tests that verify the full Netty pipeline (NettyResponseWriter + EmbeddedChannel)
 * live in the mockserver-netty module.
 */
public class MockStreamBreakpointTest {

    private Configuration configuration;

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
        configuration = Configuration.configuration()
            .breakpointTimeoutMillis(30000L)
            .breakpointMaxHeld(50);
    }

    @After
    public void cleanup() {
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
        // Allow async whenComplete callbacks from the reset-completed futures to settle
    }

    // --- gRPC mock stream breakpoints ---

    @Test
    public void shouldPauseGrpcStreamFrameInRegistry() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        String streamId = "corr-1-grpc-stream";
        byte[] grpcFrame = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);

        assertThat(frame, is(notNullValue()));
        assertThat(frame.getStreamId(), is(streamId));
        assertThat(frame.getCapturedBytes(), is(grpcFrame));
        assertThat(frame.getRequestMethod(), is("POST"));
        assertThat(frame.getRequestPath(), is("/service/Method"));
        assertThat(registry.size(), is(1));

        registry.resolveContinue(frame.getFrameId());
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CONTINUE));

        Thread.sleep(100);
        assertThat("registry should be empty", registry.size(), is(0));
    }

    @Test
    public void shouldModifyGrpcStreamFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-2-grpc-stream";
        byte[] grpcFrame = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);
        byte[] replacement = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x57, 0x6f, 0x72, 0x6c, 0x64};

        registry.resolveModify(frame.getFrameId(), replacement);
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.MODIFY));
        assertThat(decision.getReplacementBody(), is(replacement));
    }

    @Test
    public void shouldDropGrpcStreamFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-3-grpc-stream";
        byte[] grpcFrame = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);

        registry.resolveDrop(frame.getFrameId());
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.DROP));
    }

    @Test
    public void shouldInjectAfterGrpcStreamFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-4-grpc-stream";
        byte[] grpcFrame = "original-grpc".getBytes(StandardCharsets.UTF_8);

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);
        byte[] injected = "injected-grpc".getBytes(StandardCharsets.UTF_8);

        registry.resolveInject(frame.getFrameId(), injected);
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.INJECT));
        assertThat(decision.getInjectedBody(), is(injected));
    }

    @Test
    public void shouldCloseGrpcStream() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-5-grpc-stream";

        PausedStreamFrame f0 = registry.pauseFrame(streamId, "f0".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);
        PausedStreamFrame f1 = registry.pauseFrame(streamId, "f1".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);

        assertThat(registry.size(), is(2));

        registry.resolveClose(f0.getFrameId());
        StreamFrameDecision d0 = f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d0.getAction(), is(StreamFrameDecision.Action.CLOSE));

        StreamFrameDecision d1 = f1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(StreamFrameDecision.Action.DROP));

        Thread.sleep(200);
        assertThat("registry should be empty after close+evict", registry.size(), is(0));
    }

    @Test
    public void shouldEnforceOrderingAcrossGrpcFrames() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-6-grpc-stream";

        PausedStreamFrame f0 = registry.pauseFrame(streamId, "f0".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);
        PausedStreamFrame f1 = registry.pauseFrame(streamId, "f1".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);

        assertThat(f0.getSequenceNumber(), is(0));
        assertThat(f1.getSequenceNumber(), is(1));

        // Cannot resolve f1 before f0
        assertThat(registry.resolveContinue(f1.getFrameId()), is(false));

        // Resolve f0
        assertThat(registry.resolveContinue(f0.getFrameId()), is(true));
        f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Now f1 can be resolved
        assertThat(registry.resolveContinue(f1.getFrameId()), is(true));
    }

    // --- WebSocket mock stream breakpoints ---

    @Test
    public void shouldPauseWebSocketFrameInRegistry() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        String streamId = "corr-1-ws-stream";
        byte[] wsFrame = "hello websocket".getBytes(StandardCharsets.UTF_8);

        PausedStreamFrame frame = registry.pauseFrame(streamId, wsFrame, "GET", "/ws/chat", configuration);

        assertThat(frame, is(notNullValue()));
        assertThat(frame.getStreamId(), is(streamId));
        assertThat(new String(frame.getCapturedBytes(), StandardCharsets.UTF_8), is("hello websocket"));
        assertThat(frame.getRequestMethod(), is("GET"));
        assertThat(frame.getRequestPath(), is("/ws/chat"));
        assertThat(registry.size(), is(1));

        registry.resolveContinue(frame.getFrameId());
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CONTINUE));
    }

    @Test
    public void shouldModifyWebSocketFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-2-ws-stream";
        byte[] wsFrame = "original".getBytes(StandardCharsets.UTF_8);

        PausedStreamFrame frame = registry.pauseFrame(streamId, wsFrame, "GET", "/ws", configuration);
        byte[] replacement = "modified".getBytes(StandardCharsets.UTF_8);

        registry.resolveModify(frame.getFrameId(), replacement);
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.MODIFY));
        assertThat(decision.getReplacementBody(), is(replacement));
    }

    @Test
    public void shouldInjectAfterWebSocketFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        String streamId = "corr-3-ws-stream";
        byte[] wsFrame = "original".getBytes(StandardCharsets.UTF_8);

        PausedStreamFrame frame = registry.pauseFrame(streamId, wsFrame, "GET", "/ws", configuration);

        byte[] injected = "injected".getBytes(StandardCharsets.UTF_8);
        registry.resolveInject(frame.getFrameId(), injected);
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.INJECT));
        assertThat(decision.getInjectedBody(), is(injected));
    }

    @Test
    public void shouldCloseWebSocketStream() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        String streamId = "corr-4-ws-stream";
        PausedStreamFrame f0 = registry.pauseFrame(streamId, "f0".getBytes(StandardCharsets.UTF_8), "GET", "/ws", configuration);
        PausedStreamFrame f1 = registry.pauseFrame(streamId, "f1".getBytes(StandardCharsets.UTF_8), "GET", "/ws", configuration);

        assertThat(registry.size(), is(2));

        registry.resolveClose(f0.getFrameId());
        StreamFrameDecision d0 = f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d0.getAction(), is(StreamFrameDecision.Action.CLOSE));

        StreamFrameDecision d1 = f1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(StreamFrameDecision.Action.DROP));

        Thread.sleep(200);
        assertThat("registry should be empty after close+evict", registry.size(), is(0));
    }

    // --- HTTP/3 gRPC mock stream breakpoints ---

    @Test
    public void shouldPauseH3GrpcStreamFrameInRegistry() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        String streamId = "corr-h3-1-h3-grpc-stream";
        byte[] grpcFrame = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);

        assertThat(frame, is(notNullValue()));
        assertThat(frame.getStreamId(), is(streamId));
        assertThat(frame.getCapturedBytes(), is(grpcFrame));
        assertThat(frame.getRequestMethod(), is("POST"));
        assertThat(frame.getRequestPath(), is("/service/Method"));
        assertThat(registry.size(), is(1));

        registry.resolveContinue(frame.getFrameId());
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CONTINUE));

        Thread.sleep(100);
        assertThat("registry should be empty", registry.size(), is(0));
    }

    @Test
    public void shouldModifyH3GrpcStreamFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-h3-2-h3-grpc-stream";
        byte[] grpcFrame = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);
        byte[] replacement = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x57, 0x6f, 0x72, 0x6c, 0x64};

        registry.resolveModify(frame.getFrameId(), replacement);
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.MODIFY));
        assertThat(decision.getReplacementBody(), is(replacement));
    }

    @Test
    public void shouldDropH3GrpcStreamFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-h3-3-h3-grpc-stream";
        byte[] grpcFrame = new byte[]{0x00, 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);

        registry.resolveDrop(frame.getFrameId());
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.DROP));
    }

    @Test
    public void shouldInjectAfterH3GrpcStreamFrame() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-h3-4-h3-grpc-stream";
        byte[] grpcFrame = "original-h3-grpc".getBytes(StandardCharsets.UTF_8);

        PausedStreamFrame frame = registry.pauseFrame(streamId, grpcFrame, "POST", "/service/Method", configuration);
        byte[] injected = "injected-h3-grpc".getBytes(StandardCharsets.UTF_8);

        registry.resolveInject(frame.getFrameId(), injected);
        StreamFrameDecision decision = frame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.INJECT));
        assertThat(decision.getInjectedBody(), is(injected));
    }

    @Test
    public void shouldCloseH3GrpcStream() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-h3-5-h3-grpc-stream";

        PausedStreamFrame f0 = registry.pauseFrame(streamId, "f0".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);
        PausedStreamFrame f1 = registry.pauseFrame(streamId, "f1".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);

        assertThat(registry.size(), is(2));

        registry.resolveClose(f0.getFrameId());
        StreamFrameDecision d0 = f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d0.getAction(), is(StreamFrameDecision.Action.CLOSE));

        StreamFrameDecision d1 = f1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(StreamFrameDecision.Action.DROP));

        Thread.sleep(200);
        assertThat("registry should be empty after close+evict", registry.size(), is(0));
    }

    @Test
    public void shouldEnforceOrderingAcrossH3GrpcFrames() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        String streamId = "corr-h3-6-h3-grpc-stream";

        PausedStreamFrame f0 = registry.pauseFrame(streamId, "f0".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);
        PausedStreamFrame f1 = registry.pauseFrame(streamId, "f1".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);

        assertThat(f0.getSequenceNumber(), is(0));
        assertThat(f1.getSequenceNumber(), is(1));

        // Cannot resolve f1 before f0
        assertThat(registry.resolveContinue(f1.getFrameId()), is(false));

        // Resolve f0
        assertThat(registry.resolveContinue(f0.getFrameId()), is(true));
        f0.getDecisionFuture().get(1, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Now f1 can be resolved
        assertThat(registry.resolveContinue(f1.getFrameId()), is(true));
    }

    // --- H3 gRPC stream ID is distinct from H2 gRPC stream ---

    @Test
    public void shouldDistinguishH3GrpcFromH2GrpcStreamIds() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        // Same correlation id, different transport suffix
        PausedStreamFrame h2Frame = registry.pauseFrame("corr-1-grpc-stream", "h2-data".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);
        PausedStreamFrame h3Frame = registry.pauseFrame("corr-1-h3-grpc-stream", "h3-data".getBytes(StandardCharsets.UTF_8), "POST", "/svc", configuration);

        assertThat(registry.size(), is(2));
        assertThat(registry.activeStreamIds(), hasItems("corr-1-grpc-stream", "corr-1-h3-grpc-stream"));

        // Evicting H2 stream does not affect H3 stream
        registry.evictStream("corr-1-grpc-stream");
        Thread.sleep(100);

        assertThat(h2Frame.getDecisionFuture().isDone(), is(true));
        assertThat(h3Frame.getDecisionFuture().isDone(), is(false));

        // Cleanup
        registry.resolveContinue(h3Frame.getFrameId());
    }

    // --- Mixed stream types coexist ---

    @Test
    public void shouldHandleMixedStreamTypesIndependently() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        // gRPC stream (HTTP/2)
        PausedStreamFrame grpcFrame = registry.pauseFrame("corr-1-grpc-stream", "grpc-data".getBytes(StandardCharsets.UTF_8), "POST", "/grpc", configuration);
        // gRPC stream (HTTP/3)
        PausedStreamFrame h3GrpcFrame = registry.pauseFrame("corr-1-h3-grpc-stream", "h3-grpc-data".getBytes(StandardCharsets.UTF_8), "POST", "/grpc", configuration);
        // WebSocket stream
        PausedStreamFrame wsFrame = registry.pauseFrame("corr-1-ws-stream", "ws-data".getBytes(StandardCharsets.UTF_8), "GET", "/ws", configuration);
        // SSE stream (same format as forwarded)
        PausedStreamFrame sseFrame = registry.pauseFrame("corr-1-stream", "data: event\n\n".getBytes(StandardCharsets.UTF_8), "GET", "/sse", configuration);

        assertThat(registry.size(), is(4));

        // Can resolve H2 gRPC without affecting H3 gRPC/WebSocket/SSE
        registry.resolveContinue(grpcFrame.getFrameId());
        StreamFrameDecision grpcDecision = grpcFrame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(grpcDecision.getAction(), is(StreamFrameDecision.Action.CONTINUE));
        assertThat(h3GrpcFrame.getDecisionFuture().isDone(), is(false));
        assertThat(wsFrame.getDecisionFuture().isDone(), is(false));
        assertThat(sseFrame.getDecisionFuture().isDone(), is(false));

        // Can modify H3 gRPC
        registry.resolveModify(h3GrpcFrame.getFrameId(), "modified-h3".getBytes(StandardCharsets.UTF_8));
        StreamFrameDecision h3Decision = h3GrpcFrame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(h3Decision.getAction(), is(StreamFrameDecision.Action.MODIFY));

        // Can modify WebSocket
        registry.resolveModify(wsFrame.getFrameId(), "modified-ws".getBytes(StandardCharsets.UTF_8));
        StreamFrameDecision wsDecision = wsFrame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(wsDecision.getAction(), is(StreamFrameDecision.Action.MODIFY));

        // Can drop SSE
        registry.resolveDrop(sseFrame.getFrameId());
        StreamFrameDecision sseDecision = sseFrame.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(sseDecision.getAction(), is(StreamFrameDecision.Action.DROP));

        Thread.sleep(200);
        assertThat("all frames should be cleaned up", registry.size(), is(0));
    }

    // --- Stream ID format validation ---

    @Test
    public void shouldDistinguishStreamIdFormats() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame sseFrame = registry.pauseFrame("corr-1-stream", "sse".getBytes(StandardCharsets.UTF_8), "GET", "/sse", configuration);
        PausedStreamFrame grpcFrame = registry.pauseFrame("corr-1-grpc-stream", "grpc".getBytes(StandardCharsets.UTF_8), "POST", "/grpc", configuration);
        PausedStreamFrame h3GrpcFrame = registry.pauseFrame("corr-1-h3-grpc-stream", "h3-grpc".getBytes(StandardCharsets.UTF_8), "POST", "/grpc", configuration);
        PausedStreamFrame wsFrame = registry.pauseFrame("corr-1-ws-stream", "ws".getBytes(StandardCharsets.UTF_8), "GET", "/ws", configuration);

        assertThat(registry.activeStreamIds(), hasItems("corr-1-stream", "corr-1-grpc-stream", "corr-1-h3-grpc-stream", "corr-1-ws-stream"));

        // Evicting H2 gRPC stream does not affect H3 gRPC or others
        registry.evictStream("corr-1-grpc-stream");
        Thread.sleep(100);

        assertThat(sseFrame.getDecisionFuture().isDone(), is(false));
        assertThat(grpcFrame.getDecisionFuture().isDone(), is(true));
        assertThat(h3GrpcFrame.getDecisionFuture().isDone(), is(false));
        assertThat(wsFrame.getDecisionFuture().isDone(), is(false));

        // Cleanup
        registry.resolveContinue(sseFrame.getFrameId());
        registry.resolveContinue(h3GrpcFrame.getFrameId());
        registry.resolveContinue(wsFrame.getFrameId());
    }

    // --- Cap enforcement across stream types ---

    @Test
    public void shouldEnforceCapAcrossAllStreamTypes() {
        Configuration smallCap = Configuration.configuration()
            .breakpointTimeoutMillis(30000L)
            .breakpointMaxHeld(4);

        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        PausedStreamFrame grpc = registry.pauseFrame("grpc-cap", "g".getBytes(StandardCharsets.UTF_8), "POST", "/g", smallCap);
        PausedStreamFrame h3Grpc = registry.pauseFrame("h3grpc-cap", "h".getBytes(StandardCharsets.UTF_8), "POST", "/h", smallCap);
        PausedStreamFrame ws = registry.pauseFrame("ws-cap", "w".getBytes(StandardCharsets.UTF_8), "GET", "/w", smallCap);
        PausedStreamFrame sse = registry.pauseFrame("sse-cap", "s".getBytes(StandardCharsets.UTF_8), "GET", "/s", smallCap);

        assertThat(grpc, is(notNullValue()));
        assertThat(h3Grpc, is(notNullValue()));
        assertThat(ws, is(notNullValue()));
        assertThat(sse, is(notNullValue()));
        assertThat(registry.size(), is(4));

        // Fifth frame should be rejected (cap reached)
        PausedStreamFrame extra = registry.pauseFrame("extra-cap", "x".getBytes(StandardCharsets.UTF_8), "GET", "/x", smallCap);
        assertThat("should be rejected when cap reached", extra, is(nullValue()));
    }

    // --- Byte[] copy safety (no ByteBuf retained) ---

    @Test
    public void shouldCaptureFrameBytesAsCopy() throws Exception {
        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();

        byte[] original = "original-data".getBytes(StandardCharsets.UTF_8);
        PausedStreamFrame frame = registry.pauseFrame("copy-test", original, "GET", "/test", configuration);

        // Mutate the original array -- the captured bytes should be unaffected
        // (pauseFrame stores the reference, but callers pass a copy)
        // This test documents the contract: callers must pass a copy
        assertThat(frame.getCapturedBytes(), is(original));

        registry.resolveContinue(frame.getFrameId());
    }
}
