package org.mockserver.responsewriter;

import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.HttpRequest;

/**
 * Transport-neutral seam for writing a server-streaming gRPC response
 * ({@link GrpcStreamResponse}).
 * <p>
 * The default (HTTP/1.1 + HTTP/2) server-streaming path writes Netty HTTP/2 stream
 * frames directly to the channel via
 * {@link org.mockserver.mock.action.http.GrpcStreamResponseActionHandler}. The HTTP/3
 * path cannot use that handler because a QUIC stream has no HTTP/2 frame codec, so
 * {@link org.mockserver.mock.action.http.HttpActionHandler} delegates to this interface
 * when the active {@link ResponseWriter} implements it (the HTTP/3 gRPC response writer
 * does). This keeps the matching/dispatch logic in core transport-agnostic while the
 * actual HTTP/3 frame writing lives in the netty module.
 */
public interface GrpcStreamResponseWriter {

    /**
     * Write the given server-streaming gRPC response: an initial HEADERS frame,
     * one DATA frame per message (honouring per-message delays), then a trailing
     * HEADERS frame carrying {@code grpc-status} / {@code grpc-message}.
     *
     * @param grpcStreamResponse the matched server-streaming action
     * @param request            the originating request (for logging / descriptor lookup)
     */
    void writeGrpcStreamResponse(GrpcStreamResponse grpcStreamResponse, HttpRequest request);
}
