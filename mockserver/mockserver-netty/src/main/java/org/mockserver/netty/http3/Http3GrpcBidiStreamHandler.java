package org.mockserver.netty.http3;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.mockserver.grpc.GrpcBidiRuleMatcher;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.IncrementalGrpcFrameDecoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.NottableString;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives true bidirectional gRPC streaming over a single QUIC bidirectional stream
 * (HTTP/3). It is the HTTP/3 analogue of
 * {@link org.mockserver.netty.grpc.GrpcBidiStreamHandler} but, instead of being a
 * Netty pipeline handler, it is a plain helper driven by {@link Http3MockServerHandler}
 * (which already extends Netty's {@code Http3RequestStreamInboundHandler} and receives
 * frames incrementally via {@code channelRead} / {@code channelInputClosed}).
 * <p>
 * A QUIC stream is full-duplex, so the server can write response frames while the client
 * is still sending request frames. The lifecycle is:
 * <ul>
 *   <li>{@link #start()} -- write the initial response HEADERS ({@code :status=200},
 *       {@code content-type=application/grpc}, plus any configured headers) and any EAGER
 *       messages from the {@link GrpcBidiResponse} (honouring per-message delays);</li>
 *   <li>{@link #onData(byte[])} -- feed inbound bytes to the incremental gRPC frame decoder;
 *       for each complete inbound message, convert protobuf to JSON, evaluate rules in order,
 *       and emit the first matching rule's responses as DATA frames;</li>
 *   <li>{@link #onInputClosed()} -- the client half-closed (FIN); once all scheduled
 *       response writes have drained, write the trailing HEADERS carrying {@code grpc-status}
 *       and shut the QUIC stream output;</li>
 *   <li>{@link #onChannelInactive()} -- the stream/connection was torn down; clears
 *       {@code responseInProgress} on the matched expectation via the completion callback.</li>
 * </ul>
 * <p>
 * All methods run on the QUIC stream's single event-loop thread, so the {@code activeChains}
 * counter that orders the trailing HEADERS after all (possibly delayed) response writes needs
 * no synchronization. The completion callback is guarded by an {@link AtomicBoolean} so it runs
 * exactly once across every terminal path.
 */
public class Http3GrpcBidiStreamHandler {

    private final ChannelHandlerContext ctx;
    private final Descriptors.MethodDescriptor methodDescriptor;
    private final GrpcJsonMessageConverter converter;
    private final GrpcBidiResponse config;
    private final Runnable completionCallback;
    private final MockServerLogger mockServerLogger;
    private final IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();

    private boolean finished;
    private boolean inputClosed;
    // Number of in-progress message-emission chains (startup eager chain + one per inbound
    // message's rule responses). The trailing HEADERS frame is written only once the client
    // has half-closed AND no chain is still draining, so response frames always precede it.
    private int activeChains;
    private final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

    public Http3GrpcBidiStreamHandler(
        ChannelHandlerContext ctx,
        Descriptors.MethodDescriptor methodDescriptor,
        GrpcJsonMessageConverter converter,
        GrpcBidiResponse config,
        Runnable completionCallback,
        MockServerLogger mockServerLogger
    ) {
        this.ctx = ctx;
        this.methodDescriptor = methodDescriptor;
        this.converter = converter;
        this.config = config;
        this.completionCallback = completionCallback;
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Write the initial response HEADERS and any eager messages. The top-level action delay
     * ({@link GrpcBidiResponse#getDelay()}), if configured, delays the eager message stream
     * (the HEADERS are sent promptly so inbound DATA frames never race ahead of them).
     */
    public void start() {
        writeInitialHeaders();

        long actionDelayMillis = (config.getDelay() != null) ? config.getDelay().sampleValueMillis() : 0;
        activeChains++; // startup (eager) chain in progress
        Runnable emitEager = () -> emitSequential(config.getMessages(), 0, () -> {
            activeChains--;
            maybeFinish();
        });
        if (actionDelayMillis > 0) {
            ctx.executor().schedule(emitEager, actionDelayMillis, TimeUnit.MILLISECONDS);
        } else {
            emitEager.run();
        }
    }

    /**
     * Feed inbound request bytes: decode complete gRPC frames, convert each to JSON,
     * and emit the first matching rule's responses.
     */
    public void onData(byte[] bytes) {
        if (finished) {
            return;
        }
        try {
            List<byte[]> completedMessages = decoder.feed(bytes);
            for (byte[] message : completedMessages) {
                String inboundJson = converter.toJson(message, methodDescriptor.getInputType());
                List<GrpcStreamMessage> responses = firstMatchingResponses(inboundJson);
                if (responses != null && !responses.isEmpty()) {
                    activeChains++;
                    emitSequential(responses, 0, () -> {
                        activeChains--;
                        maybeFinish();
                    });
                }
            }
        } catch (GrpcException e) {
            GrpcStatusMapper.GrpcStatusCode code = (e.getMessage() != null && e.getMessage().contains("exceeded maximum"))
                ? GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED
                : GrpcStatusMapper.GrpcStatusCode.INTERNAL;
            writeErrorTrailer(code, e.getMessage());
        } catch (Exception e) {
            writeErrorTrailer(GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                e.getMessage() != null ? e.getMessage() : "internal error");
        }
    }

    /**
     * The client half-closed (END_STREAM). Finish once all scheduled responses have drained.
     */
    public void onInputClosed() {
        inputClosed = true;
        maybeFinish();
    }

    /**
     * The QUIC stream / connection was torn down. Clear responseInProgress so a times-limited
     * expectation is not left stuck when a bidi stream is abandoned without a clean END_STREAM.
     */
    public void onChannelInactive() {
        invokeCompletionCallback();
    }

    private List<GrpcStreamMessage> firstMatchingResponses(String inboundJson) {
        if (config.getRules() == null) {
            return null;
        }
        for (GrpcBidiRule rule : config.getRules()) {
            if (GrpcBidiRuleMatcher.matches(rule, inboundJson)) {
                return rule.getResponses(); // first match wins (may be null/empty -> no response)
            }
        }
        return null; // no match -> no response (documented behaviour)
    }

    private void writeInitialHeaders() {
        DefaultHttp3HeadersFrame headersFrame = GrpcHttp3Adapter.buildInitialHeadersFrame();
        if (config.getHeaders() != null) {
            for (Header entry : config.getHeaders().getEntries()) {
                for (NottableString value : entry.getValues()) {
                    headersFrame.headers().add(entry.getName().getValue().toLowerCase(), value.getValue());
                }
            }
        }
        ctx.write(headersFrame);
        ctx.flush();
    }

    /**
     * Emit a list of messages sequentially, honouring per-message {@link Delay}, then run
     * {@code onDone}. Chaining preserves ordering even when delays differ.
     */
    private void emitSequential(List<GrpcStreamMessage> messages, int index, Runnable onDone) {
        if (messages == null || index >= messages.size() || !ctx.channel().isActive()) {
            onDone.run();
            return;
        }
        GrpcStreamMessage message = messages.get(index);
        long delayMillis = (message.getDelay() != null) ? message.getDelay().sampleValueMillis() : 0;
        Runnable writeNext = () -> {
            writeResponseMessage(message.getJson());
            emitSequential(messages, index + 1, onDone);
        };
        if (delayMillis > 0) {
            ctx.executor().schedule(writeNext, delayMillis, TimeUnit.MILLISECONDS);
        } else {
            writeNext.run();
        }
    }

    private void writeResponseMessage(String json) {
        byte[] responseProto = converter.toProtobuf(json, methodDescriptor.getOutputType());
        byte[] framedResponse = GrpcFrameCodec.encode(responseProto);
        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(framedResponse)));
    }

    private void maybeFinish() {
        if (inputClosed && activeChains == 0 && !finished) {
            finish();
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;

        String statusCode = "0";
        String statusMessage = null;
        if (config.getStatusName() != null) {
            statusCode = String.valueOf(GrpcStatusMapper.fromName(config.getStatusName()).getCode());
        }
        if (config.getStatusMessage() != null && !config.getStatusMessage().isEmpty()) {
            statusMessage = config.getStatusMessage();
        }

        DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(statusCode, statusMessage);
        ctx.writeAndFlush(trailers).addListener(future -> {
            if (Boolean.TRUE.equals(config.getCloseConnection())) {
                ctx.close();
            } else if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        });

        invokeCompletionCallback();
    }

    private void writeErrorTrailer(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        if (finished) {
            return;
        }
        finished = true;
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat("gRPC bidi stream error over HTTP/3:{}")
                .setArguments(message)
        );
        DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
            String.valueOf(statusCode.getCode()), message
        );
        ctx.writeAndFlush(trailers).addListener(future -> {
            if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        });
        invokeCompletionCallback();
    }

    private void invokeCompletionCallback() {
        if (completionCallback != null && callbackInvoked.compareAndSet(false, true)) {
            try {
                completionCallback.run();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }
}
