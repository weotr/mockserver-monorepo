package org.mockserver.netty.http3;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

/**
 * A {@link ResponseWriter} that serialises the MockServer {@link HttpResponse}
 * as HTTP/3 frames and writes them to a QUIC stream channel.
 * <p>
 * This allows the standard request-processing pipeline ({@code HttpState},
 * {@code HttpActionHandler}) to write responses identically regardless of
 * whether the request arrived via HTTP/1.1, HTTP/2, or HTTP/3.
 * <p>
 * <strong>Streaming support:</strong> when the response carries a
 * {@link StreamingBody} (SSE, chunked proxy forwarding, LLM streaming),
 * the headers are sent immediately and each chunk is forwarded as an HTTP/3
 * DATA frame. The QUIC stream output is shut down when the stream completes.
 * Backpressure is implemented via {@link StreamingBody#requestMore()}: each
 * chunk write completion triggers the next upstream read.
 */
public class Http3ResponseWriter extends ResponseWriter {

    private final ChannelHandlerContext ctx;

    public Http3ResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (response == null) {
            response = HttpResponse.notFoundResponse();
        }

        if (response.getStreamingBody() != null) {
            writeStreamingResponse(request, response);
        } else {
            writeStaticResponse(response);
        }
    }

    /**
     * Write a streaming response: send headers immediately, then subscribe to the
     * {@link StreamingBody} to forward each chunk as an HTTP/3 DATA frame. When the
     * stream completes (or errors), shut down the QUIC stream output.
     */
    private void writeStreamingResponse(HttpRequest request, HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();

        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.DEBUG)
                .setHttpRequest(request)
                .setMessageFormat("streaming response over HTTP/3 for request:{}")
                .setArguments(request)
        );

        // Send the response headers immediately (without SHUTDOWN_OUTPUT)
        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);
        ctx.writeAndFlush(headersFrame);

        // Subscribe to the streaming body to forward chunks as HTTP/3 DATA frames.
        // After each chunk write completes, call streamingBody.requestMore() to trigger
        // the next upstream read -- this implements backpressure so a slow client does
        // not cause unbounded buffering.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                if (ctx.channel().isActive()) {
                    DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
                        Unpooled.copiedBuffer(chunk)
                    );
                    ctx.writeAndFlush(dataFrame).addListener(future ->
                        streamingBody.requestMore()
                    );
                } else {
                    // Channel is no longer active; still request more so the upstream can
                    // detect the closed channel on the next read and clean up.
                    streamingBody.requestMore();
                }
            },
            // onComplete -- flush an empty DATA frame to ensure all prior chunk
            // writes have drained through the QUIC pipeline before shutting down
            // the stream output (avoids truncation race with pending async writes)
            () -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER))
                        .addListener(future -> shutdownQuicStreamOutput());
                }
            },
            // onError
            error -> {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setHttpRequest(request)
                        .setMessageFormat("streaming response error over HTTP/3 for request:{}error:{}")
                        .setArguments(request, error.getMessage())
                        .setThrowable(error)
                );
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER))
                        .addListener(future -> shutdownQuicStreamOutput());
                }
            }
        );
    }

    /**
     * Write a static (non-streaming) response: headers + optional body DATA frame,
     * then shut down the QUIC stream output.
     */
    private void writeStaticResponse(HttpResponse response) {
        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);
        DefaultHttp3DataFrame dataFrame = Http3RequestBridge.toHttp3DataFrame(response);

        ctx.write(headersFrame);
        if (dataFrame != null) {
            ctx.writeAndFlush(dataFrame)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            ctx.flush();
            shutdownQuicStreamOutput();
        }
    }

    /**
     * Shut down the output side of the QUIC stream, signalling to the peer that no
     * more data will be sent on this stream. Safe to call multiple times.
     */
    private void shutdownQuicStreamOutput() {
        if (ctx.channel() instanceof QuicStreamChannel) {
            ((QuicStreamChannel) ctx.channel()).shutdownOutput();
        }
    }
}
