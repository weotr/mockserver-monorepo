package org.mockserver.netty.http3;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher;
import org.mockserver.mock.breakpoint.BreakpointMatcherRegistry;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.serialization.WebSocketMessageSerializer;
import org.mockserver.serialization.model.PausedStreamFrameDTO;
import org.mockserver.serialization.model.StreamFrameDecisionDTO;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for inbound (client-to-server) breakpoint interception in
 * {@link Http3GrpcBidiStreamHandler} — the HTTP/3 analogue of the HTTP/2 inbound bidi
 * breakpoints in {@link org.mockserver.netty.grpc.GrpcBidiStreamHandler}.
 * <p>
 * The handler is driven directly (it is a plain helper, not a Netty pipeline handler) with a
 * mocked QUIC-stream {@link ChannelHandlerContext} whose executor runs inline
 * ({@link ImmediateEventExecutor}), and a real {@link WebSocketClientRegistry} backed by an
 * {@link EmbeddedChannel} that simulates a connected dashboard/callback client. Inbound frames
 * are fed as partial gRPC bytes so the incremental decoder never completes a message — this
 * isolates the parking / ordering / eviction logic without needing a real protobuf converter.
 * <p>
 * This test mutates the global breakpoint singletons ({@link StreamFrameCallbackDispatcher},
 * {@link StreamFrameBreakpointRegistry}); mockserver-netty runs unit tests sequentially in a
 * single fork, and {@code setUp}/{@code tearDown} reset every breakpoint singleton so no state
 * leaks to or from neighbouring tests.
 */
public class Http3GrpcBidiInboundBreakpointTest {

    private static final String CLIENT_ID = "test-h3-bidi-inbound-client";
    private static final String STREAM_ID = "grpc-bidi-inbound-/BidiService/Chat-h3-test";
    private static final String BREAKPOINT_ID = "bp-h3-1";

    // A clearly-incomplete gRPC frame header (1-byte flag + truncated 4-byte length): the
    // incremental decoder buffers it and never yields a completed message, so the converter
    // is never invoked. Each call is a distinct array so identity assertions stay meaningful.
    private static byte[] partialFrame() {
        return new byte[]{0, 0, 0};
    }

    private Configuration configuration;
    private MockServerLogger logger;
    private WebSocketClientRegistry webSocketClientRegistry;
    private WebSocketMessageSerializer serializer;
    private EmbeddedChannel clientChannel;
    private Descriptors.MethodDescriptor methodDescriptor;

    @Before
    public void setUp() throws Exception {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();

        configuration = Configuration.configuration()
            .breakpointTimeoutMillis(30_000L)
            .breakpointMaxHeld(50);
        logger = new MockServerLogger();
        webSocketClientRegistry = new WebSocketClientRegistry(configuration, logger);
        serializer = new WebSocketMessageSerializer(logger);

        clientChannel = new EmbeddedChannel();
        ChannelHandlerContext clientCtx = mock(ChannelHandlerContext.class);
        when(clientCtx.channel()).thenReturn(clientChannel);
        webSocketClientRegistry.registerClient(CLIENT_ID, clientCtx);
        clientChannel.readOutbound(); // drain registration confirmation

        methodDescriptor = bidiMethodDescriptor();
    }

    @After
    public void tearDown() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
        if (clientChannel.isOpen()) {
            clientChannel.close();
        }
    }

    @Test
    public void defaultOffProcessesInlineWithoutDispatch() {
        // given: a handler with NO inbound breakpoint identity (default path)
        Http3GrpcBidiStreamHandler handler = new Http3GrpcBidiStreamHandler(
            mockStreamCtx(), methodDescriptor, null, new GrpcBidiResponse(), () -> {
            }, logger);

        // when: an inbound frame arrives
        handler.onData(partialFrame());

        // then: nothing is dispatched to the callback client
        assertThat(clientChannel.readOutbound(), is(nullValue()));
        assertThat(StreamFrameCallbackDispatcher.getInstance().inFlightCount(), is(0));
    }

    @Test
    public void inboundFrameIsParkedAndDispatchedWithInboundDirection() throws Exception {
        Http3GrpcBidiStreamHandler handler = activeHandler(mockStreamCtx(), () -> {
        });

        handler.onData(partialFrame());

        // a single paused-frame message is sent to the callback client
        PausedStreamFrameDTO dto = readPausedFrame();
        assertThat(dto, is(notNullValue()));
        assertThat(dto.getDirection(), is("INBOUND"));
        assertThat(dto.getPhase(), is("INBOUND_STREAM"));
        assertThat(dto.getStreamId(), is(STREAM_ID));
        assertThat(dto.getBreakpointId(), is(BREAKPOINT_ID));
        assertThat(dto.getSequenceNumber(), is(0));
        assertThat(StreamFrameCallbackDispatcher.getInstance().inFlightCount(), is(1));
    }

    @Test
    public void concurrentInboundFramesAreDispatchedOneAtATimeInOrder() throws Exception {
        Http3GrpcBidiStreamHandler handler = activeHandler(mockStreamCtx(), () -> {
        });

        // first frame parks
        handler.onData(partialFrame());
        PausedStreamFrameDTO first = readPausedFrame();
        assertThat(first.getSequenceNumber(), is(0));

        // second frame arrives while the first is held -> buffered, NOT dispatched yet
        handler.onData(partialFrame());
        assertThat("only one frame may be in flight at a time", readPausedFrame(), is(nullValue()));
        assertThat(StreamFrameCallbackDispatcher.getInstance().inFlightCount(), is(1));

        // resolve the first frame (CONTINUE) -> the buffered second frame is now dispatched in order
        reply(first.getCorrelationId(), "CONTINUE", null);
        PausedStreamFrameDTO second = readPausedFrame();
        assertThat(second, is(notNullValue()));
        assertThat(second.getSequenceNumber(), is(1));
        assertThat(StreamFrameCallbackDispatcher.getInstance().inFlightCount(), is(1));

        // drain the second so no dispatch is left hanging
        reply(second.getCorrelationId(), "CONTINUE", null);
        assertThat(StreamFrameCallbackDispatcher.getInstance().inFlightCount(), is(0));
    }

    @Test
    public void channelInactiveInvokesCompletionCallbackAndClearsHeldFrames() throws Exception {
        AtomicInteger completions = new AtomicInteger(0);
        Http3GrpcBidiStreamHandler handler = activeHandler(mockStreamCtx(), completions::incrementAndGet);

        // park one frame, then buffer a second behind it
        handler.onData(partialFrame());
        readPausedFrame();
        handler.onData(partialFrame());

        // when: the QUIC stream is torn down
        handler.onChannelInactive();

        // then: the completion callback fires exactly once (clears responseInProgress) and the
        // per-stream registry entry is evicted; a second teardown is a safe no-op.
        assertThat(completions.get(), is(1));
        assertThat(StreamFrameBreakpointRegistry.getInstance().activeStreamIds(), not(hasItem(STREAM_ID)));
        handler.onChannelInactive();
        assertThat(completions.get(), is(1));
    }

    // ---- helpers ----

    private Http3GrpcBidiStreamHandler activeHandler(ChannelHandlerContext ctx, Runnable completionCallback) {
        return new Http3GrpcBidiStreamHandler(
            ctx, methodDescriptor, null, new GrpcBidiResponse(), completionCallback, logger,
            configuration, STREAM_ID, CLIENT_ID, BREAKPOINT_ID, webSocketClientRegistry);
    }

    private PausedStreamFrameDTO readPausedFrame() throws Exception {
        TextWebSocketFrame sent = clientChannel.readOutbound();
        if (sent == null) {
            return null;
        }
        Object message = serializer.deserialize(sent.text());
        assertThat(message, instanceOf(PausedStreamFrameDTO.class));
        return (PausedStreamFrameDTO) message;
    }

    private void reply(String correlationId, String action, byte[] body) throws Exception {
        StreamFrameDecisionDTO reply = new StreamFrameDecisionDTO()
            .setCorrelationId(correlationId)
            .setAction(action);
        if (body != null) {
            reply.setBody(java.util.Base64.getEncoder().encodeToString(body));
        }
        webSocketClientRegistry.receivedTextWebSocketFrame(new TextWebSocketFrame(serializer.serialize(reply)));
    }

    private ChannelHandlerContext mockStreamCtx() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.executor()).thenReturn(ImmediateEventExecutor.INSTANCE);
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(org.mockito.ArgumentMatchers.any())).thenReturn(future);
        when(ctx.write(org.mockito.ArgumentMatchers.any())).thenReturn(future);
        when(ctx.writeAndFlush(org.mockito.ArgumentMatchers.any())).thenReturn(future);
        return ctx;
    }

    /**
     * Builds a real client+server-streaming (bidi) gRPC {@link Descriptors.MethodDescriptor} from
     * an inline proto, avoiding the need to mock protobuf's final descriptor classes.
     */
    private static Descriptors.MethodDescriptor bidiMethodDescriptor() throws Exception {
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("h3bidi.proto")
            .setSyntax("proto3")
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Msg"))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BidiService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Chat")
                    .setInputType(".Msg")
                    .setOutputType(".Msg")
                    .setClientStreaming(true)
                    .setServerStreaming(true)))
            .build();
        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(
            fileProto, new Descriptors.FileDescriptor[]{});
        return fd.findServiceByName("BidiService").findMethodByName("Chat");
    }
}
