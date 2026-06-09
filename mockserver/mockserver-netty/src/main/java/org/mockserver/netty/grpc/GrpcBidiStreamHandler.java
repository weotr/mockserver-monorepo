package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcBidiRuleMatcher;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.IncrementalGrpcFrameDecoder;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.NottableString;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Per-stream handler for true bidirectional gRPC streaming. NOT {@code @Sharable} --
 * holds per-stream state (the incremental frame decoder, finished guard).
 * <p>
 * <strong>Phase 3b behaviour (rule-driven via GrpcBidiResponse):</strong>
 * <ul>
 *   <li>On {@link Http2HeadersFrame}: applies the top-level action delay (if configured)
 *       before writing the initial response HEADERS ({@code :status=200},
 *       {@code content-type=application/grpc}, plus any configured headers from the
 *       GrpcBidiResponse, {@code endStream=false}). Then writes any EAGER messages from
 *       the response, honouring per-message {@link GrpcStreamMessage#getDelay()} via
 *       event-loop scheduling (messages are chained so ordering is preserved and the
 *       trailing grpc-status is written only after all scheduled messages complete).</li>
 *   <li>On {@link Http2DataFrame}: feeds content bytes to {@link IncrementalGrpcFrameDecoder};
 *       for each complete inbound message, converts to JSON via the converter, evaluates
 *       rules in order (first match emits its responses as DATA frames). If no rule matches,
 *       no response is emitted. If the frame has {@code endStream=true}, calls
 *       {@link #finish(ChannelHandlerContext)}.</li>
 *   <li>{@code finish()}: writes trailing HEADERS with configured grpc-status and
 *       {@code endStream=true}. Guarded to run at most once.</li>
 * </ul>
 * <p>
 * The handler supports two modes:
 * <ul>
 *   <li><strong>Phase 3b (GrpcBidiResponse-driven):</strong> Constructed with a
 *       {@link GrpcBidiResponse} config; eager messages, rules, status, and headers come
 *       from the config.</li>
 *   <li><strong>Phase 3a (legacy responder function):</strong> Constructed with a
 *       {@link Function} responder for backward compatibility with existing tests.</li>
 * </ul>
 * <p>
 * Flow control: the channel's autoRead is set to {@code false} when this handler is
 * added; after processing each inbound frame, {@code ctx.read()} is called to request
 * the next frame. If the decoder's buffer cap is exceeded, a RESOURCE_EXHAUSTED trailing
 * status is written and the stream is finished.
 * <p>
 * Error handling: exceptions during channelRead are caught and result in an INTERNAL
 * grpc-status trailer, never an uncaught exception propagating up the pipeline.
 */
public class GrpcBidiStreamHandler extends ChannelInboundHandlerAdapter {

    private final Descriptors.MethodDescriptor methodDescriptor;
    private final GrpcJsonMessageConverter converter;
    private final IncrementalGrpcFrameDecoder decoder;
    private volatile boolean finished;

    // Phase 3a mode: function-based responder
    private final Function<String, List<String>> responder;

    // Phase 3b mode: GrpcBidiResponse-driven
    private final GrpcBidiResponse config;

    // Completion callback: invoked exactly once when the stream finishes (normal or error)
    // or when the channel becomes inactive (client disconnect / abandoned stream).
    // Clears responseInProgress on the matched expectation so a times-limited expectation
    // is not left stuck. May be null (e.g. in Phase 3a / testing without HttpState).
    private final Runnable completionCallback;

    // Self-guard for invokeCompletionCallback: ensures the callback runs exactly once
    // across ALL terminal paths (finish, writeTrailer error, channelInactive, exceptionCaught).
    // Separate from the 'finished' flag because channelInactive can fire on an abandoned
    // stream that never reached finish() or writeTrailer().
    private final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

    /**
     * Phase 3a constructor: function-based responder (backward compatible).
     *
     * @param methodDescriptor the resolved gRPC method descriptor
     * @param converter        JSON/protobuf converter for the method's message types
     * @param responder        maps an inbound message JSON string to a list of response
     *                         JSON strings; for 3a the default is echo: returns {@code [inboundJson]}
     */
    public GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        Function<String, List<String>> responder
    ) {
        this(methodDescriptor, converter, responder, null, new IncrementalGrpcFrameDecoder(), null);
    }

    /**
     * Phase 3b constructor: GrpcBidiResponse-driven (without completion callback).
     *
     * @param methodDescriptor the resolved gRPC method descriptor
     * @param converter        JSON/protobuf converter for the method's message types
     * @param config           the GrpcBidiResponse configuration from the matched expectation
     */
    public GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        GrpcBidiResponse config
    ) {
        this(methodDescriptor, converter, null, config, new IncrementalGrpcFrameDecoder(), null);
    }

    /**
     * Phase 3b constructor: GrpcBidiResponse-driven with completion callback.
     * The completion callback is invoked exactly once when the stream finishes (or errors),
     * clearing {@code responseInProgress} on the matched expectation.
     *
     * @param methodDescriptor   the resolved gRPC method descriptor
     * @param converter          JSON/protobuf converter for the method's message types
     * @param config             the GrpcBidiResponse configuration from the matched expectation
     * @param completionCallback invoked once on stream finish to clear responseInProgress
     */
    public GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        GrpcBidiResponse config,
        Runnable completionCallback
    ) {
        this(methodDescriptor, converter, null, config, new IncrementalGrpcFrameDecoder(), completionCallback);
    }

    /**
     * Visible-for-testing constructor that accepts a custom decoder (e.g. with a small cap).
     */
    GrpcBidiStreamHandler(
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        Function<String, List<String>> responder,
        GrpcBidiResponse config,
        IncrementalGrpcFrameDecoder decoder,
        Runnable completionCallback
    ) {
        this.methodDescriptor = methodDescriptor;
        this.converter = converter;
        this.responder = responder;
        this.config = config;
        this.decoder = decoder;
        this.completionCallback = completionCallback;
        this.finished = false;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ctx.channel().config().setAutoRead(false);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame) {
                handleHeaders(ctx, (Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                handleData(ctx, (Http2DataFrame) msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        } catch (GrpcException e) {
            if (e.getMessage() != null && e.getMessage().contains("exceeded maximum")) {
                writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED, e.getMessage());
            } else {
                writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL, e.getMessage());
            }
        } catch (Exception e) {
            writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                e.getMessage() != null ? e.getMessage() : "internal error");
        }
    }

    private void handleHeaders(ChannelHandlerContext ctx, Http2HeadersFrame headersFrame) {
        // Apply the top-level action delay (if configured) before writing initial HEADERS.
        // This mirrors how HttpActionHandler applies action.getDelay() via the Scheduler.
        Delay actionDelay = (config != null) ? config.getDelay() : null;
        long actionDelayMillis = (actionDelay != null) ? actionDelay.sampleValueMillis() : 0;

        Runnable writeInitialResponse = () -> {
            // Write initial response headers
            DefaultHttp2Headers responseHeaders = new DefaultHttp2Headers();
            responseHeaders.status("200");
            responseHeaders.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);

            // Add configured headers from GrpcBidiResponse
            if (config != null && config.getHeaders() != null) {
                Headers configHeaders = config.getHeaders();
                for (Header entry : configHeaders.getEntries()) {
                    for (NottableString value : entry.getValues()) {
                        responseHeaders.add(entry.getName().getValue().toLowerCase(), value.getValue());
                    }
                }
            }

            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, false));

            // Send eager messages if configured (Phase 3b), honouring per-message delay
            if (config != null && config.getMessages() != null && !config.getMessages().isEmpty()) {
                scheduleMessages(config.getMessages(), 0, ctx, () -> {
                    if (headersFrame.isEndStream()) {
                        finish(ctx);
                    } else {
                        ctx.read();
                    }
                });
            } else {
                if (headersFrame.isEndStream()) {
                    finish(ctx);
                } else {
                    ctx.read();
                }
            }
        };

        if (actionDelayMillis > 0) {
            ctx.executor().schedule(writeInitialResponse, actionDelayMillis, TimeUnit.MILLISECONDS);
        } else {
            writeInitialResponse.run();
        }
    }

    private void handleData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            byte[] bytes = new byte[dataFrame.content().readableBytes()];
            dataFrame.content().readBytes(bytes);

            List<byte[]> completedMessages = decoder.feed(bytes);
            boolean endStream = dataFrame.isEndStream();

            // The continuation to run after all inbound messages from this DATA frame
            // have had their responses (possibly delayed) written.
            Runnable afterAllMessages = () -> {
                if (endStream) {
                    finish(ctx);
                } else {
                    ctx.read();
                }
            };

            if (completedMessages.isEmpty()) {
                afterAllMessages.run();
                return;
            }

            // Process inbound messages sequentially, chaining the continuation through
            // delayed rule responses so that finish/read happens only after all scheduled
            // messages are written (preserving interleaving and finish ordering).
            processInboundMessages(ctx, completedMessages, 0, afterAllMessages);

        } finally {
            dataFrame.release();
        }
    }

    /**
     * Recursively processes decoded inbound messages, chaining rule-response scheduling
     * so that the {@code afterAll} continuation runs only after the last message's responses
     * (including any per-message delays) have been written.
     */
    private void processInboundMessages(ChannelHandlerContext ctx, List<byte[]> messages, int index, Runnable afterAll) {
        if (index >= messages.size()) {
            afterAll.run();
            return;
        }

        String inboundJson = converter.toJson(messages.get(index), methodDescriptor.getInputType());
        Runnable processNext = () -> processInboundMessages(ctx, messages, index + 1, afterAll);

        if (config != null) {
            // Phase 3b: rule-driven matching with per-message delay
            processWithRules(ctx, inboundJson, processNext);
        } else if (responder != null) {
            // Phase 3a: function-based responder (no delay support)
            List<String> responseJsons = responder.apply(inboundJson);
            if (responseJsons != null) {
                for (String responseJson : responseJsons) {
                    writeGrpcMessage(ctx, responseJson);
                }
            }
            processNext.run();
        } else {
            processNext.run();
        }
    }

    private void processWithRules(ChannelHandlerContext ctx, String inboundJson, Runnable afterResponses) {
        if (config.getRules() == null) {
            afterResponses.run();
            return;
        }
        for (GrpcBidiRule rule : config.getRules()) {
            if (matchesRule(rule, inboundJson)) {
                if (rule.getResponses() != null && !rule.getResponses().isEmpty()) {
                    scheduleMessages(rule.getResponses(), 0, ctx, afterResponses);
                } else {
                    afterResponses.run();
                }
                return; // first match wins
            }
        }
        // no match -> no response (documented behaviour)
        afterResponses.run();
    }

    /**
     * Matches the inbound message JSON against the rule's matchJson pattern.
     * Delegates to {@link GrpcBidiRuleMatcher#matches(GrpcBidiRule, String)}: exact string
     * match first, then a DOTALL regex match (so {@code '.'} matches the newlines that
     * protobuf's {@code JsonFormat.printer()} emits), with {@code NottableString} negation.
     * Retained as a package-private method so existing unit tests keep exercising it.
     */
    boolean matchesRule(GrpcBidiRule rule, String inboundJson) {
        // Delegate to the transport-neutral matcher so HTTP/2 and HTTP/3 bidi share
        // identical rule-matching semantics (exact, then DOTALL regex, with negation).
        return GrpcBidiRuleMatcher.matches(rule, inboundJson);
    }

    /**
     * Schedule a list of {@link GrpcStreamMessage} for writing, honouring per-message
     * {@link Delay} via the channel's event-loop executor. Messages are chained so each
     * writes only after the previous completes (preserving order even with varying delays).
     * The {@code afterAll} continuation runs after the last message is written.
     * <p>
     * Mirrors the recursive scheduling pattern of
     * {@link org.mockserver.mock.action.http.GrpcStreamResponseActionHandler#scheduleMessages}.
     */
    private void scheduleMessages(List<GrpcStreamMessage> messages, int index, ChannelHandlerContext ctx, Runnable afterAll) {
        if (index >= messages.size()) {
            afterAll.run();
            return;
        }
        GrpcStreamMessage message = messages.get(index);
        Delay delay = message.getDelay();
        long delayMillis = (delay != null) ? delay.sampleValueMillis() : 0;

        Runnable writeAndContinue = () -> {
            writeGrpcMessage(ctx, message.getJson());
            scheduleMessages(messages, index + 1, ctx, afterAll);
        };

        if (delayMillis > 0) {
            ctx.executor().schedule(writeAndContinue, delayMillis, TimeUnit.MILLISECONDS);
        } else {
            writeAndContinue.run();
        }
    }

    private void writeGrpcMessage(ChannelHandlerContext ctx, String json) {
        byte[] responseProto = converter.toProtobuf(json, methodDescriptor.getOutputType());
        byte[] framedResponse = GrpcFrameCodec.encode(responseProto);
        ctx.writeAndFlush(new DefaultHttp2DataFrame(
            Unpooled.wrappedBuffer(framedResponse), false));
    }

    private void finish(ChannelHandlerContext ctx) {
        if (finished) {
            return;
        }
        finished = true;

        // Determine grpc-status from config
        String statusCode = "0";
        String statusMessage = null;
        if (config != null) {
            if (config.getStatusName() != null) {
                GrpcStatusMapper.GrpcStatusCode code = GrpcStatusMapper.fromName(config.getStatusName());
                statusCode = String.valueOf(code.getCode());
            }
            statusMessage = config.getStatusMessage();
        }

        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(GrpcStatusMapper.GRPC_STATUS_HEADER, statusCode);
        if (statusMessage != null) {
            trailers.set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, statusMessage);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));

        // Clear responseInProgress on the matched expectation so a times-limited
        // expectation is not left stuck after the bidi stream completes.
        invokeCompletionCallback();

        // Close connection if configured
        if (config != null && Boolean.TRUE.equals(config.getCloseConnection())) {
            ctx.close();
        }
    }

    private void writeTrailer(ChannelHandlerContext ctx, GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        if (finished) {
            return;
        }
        finished = true;
        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.set(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()));
        if (message != null) {
            trailers.set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));

        // Clear responseInProgress on error path too
        invokeCompletionCallback();
    }

    /**
     * Invokes the completion callback exactly once, guarded by an {@link AtomicBoolean} CAS.
     * This is safe to call from any terminal path (finish, writeTrailer, channelInactive,
     * exceptionCaught) -- the callback will execute on the first invocation and be a no-op
     * on all subsequent calls, regardless of which path fires first.
     * <p>
     * Clears {@code responseInProgress} on the matched expectation so it can be removed
     * when its Times are exhausted.
     */
    private void invokeCompletionCallback() {
        if (completionCallback != null && callbackInvoked.compareAndSet(false, true)) {
            try {
                completionCallback.run();
            } catch (Exception ignored) {
                // Best-effort: don't let post-processing failure break the stream teardown
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        writeTrailer(ctx, GrpcStatusMapper.GrpcStatusCode.INTERNAL,
            cause.getMessage() != null ? cause.getMessage() : "internal error");
    }

    /**
     * Handles channel deactivation (client disconnect, connection closed, abandoned stream).
     * If the stream has not already finished via {@link #finish} or {@link #writeTrailer},
     * the completion callback is invoked to clear {@code responseInProgress} on the matched
     * expectation. This prevents a times-limited expectation from being stuck forever when
     * a bidi stream is abandoned without a clean END_STREAM.
     * <p>
     * The callback is self-guarded by {@link #callbackInvoked} (AtomicBoolean CAS), so it
     * runs exactly once across all terminal paths: normal finish, error trailer, channel
     * inactive, and exception caught.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        invokeCompletionCallback();
        super.channelInactive(ctx);
    }
}
