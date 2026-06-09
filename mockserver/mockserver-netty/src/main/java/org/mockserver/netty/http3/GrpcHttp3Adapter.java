package org.mockserver.netty.http3;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import com.google.protobuf.Descriptors;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

/**
 * Adapter that bridges gRPC request/response framing for the HTTP/3 path.
 * <p>
 * Reuses the existing {@link GrpcFrameCodec}, {@link GrpcProtoDescriptorStore},
 * and {@link GrpcJsonMessageConverter} from mockserver-core -- no logic is
 * duplicated. The adapter handles:
 * <ul>
 *   <li><strong>Inbound:</strong> detecting gRPC content-type, decoding the
 *       5-byte length-prefixed gRPC frame(s) from the request body, converting
 *       protobuf to JSON via the descriptor store, and tagging the request
 *       with {@code x-grpc-service} / {@code x-grpc-method} so the response
 *       path can re-encode.</li>
 *   <li><strong>Outbound:</strong> converting the matched response's JSON body
 *       back to gRPC-framed protobuf, building the initial HTTP/3 HEADERS
 *       frame (without grpc-status), the DATA frame (gRPC framed body), and a
 *       separate trailing HEADERS frame with grpc-status/grpc-message -- the
 *       correct wire framing that gRPC clients expect over HTTP/3.</li>
 * </ul>
 * <p>
 * This class is stateless and thread-safe; all state is passed via method
 * parameters.
 */
public final class GrpcHttp3Adapter {

    private GrpcHttp3Adapter() {
        // utility class
    }

    /**
     * Detect whether the given content-type indicates a gRPC request.
     * Delegates to {@link GrpcStatusMapper#isGrpcContentType(String)}.
     */
    public static boolean isGrpcRequest(String contentType) {
        return GrpcStatusMapper.isGrpcContentType(contentType);
    }

    /**
     * Transform a gRPC request for the MockServer matching pipeline: decode the
     * gRPC length-prefixed message(s), convert protobuf to JSON via the
     * descriptor store, and tag the request with service/method markers.
     *
     * @param request         the raw HttpRequest with gRPC-framed binary body
     * @param descriptorStore the gRPC proto descriptor store
     * @return a transformed HttpRequest with a JSON body suitable for matching
     * @throws GrpcException if the gRPC path or framing is invalid
     */
    public static HttpRequest transformGrpcRequest(HttpRequest request, GrpcProtoDescriptorStore descriptorStore) {
        String path = request.getPath() != null ? request.getPath().getValue() : "";
        String[] parts = parseGrpcPath(path);
        String serviceName = parts[0];
        String methodName = parts[1];

        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        if (methodDescriptor == null) {
            throw new GrpcException("unknown gRPC method: " + serviceName + "/" + methodName);
        }

        byte[] bodyBytes = request.getBodyAsRawBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return request
                .clone()
                .withHeader("x-grpc-service", serviceName)
                .withHeader("x-grpc-method", methodName)
                .withHeader("x-grpc-original-content-type", request.getFirstHeader("content-type"));
        }

        List<byte[]> messages = GrpcFrameCodec.decode(bodyBytes);
        if (messages.isEmpty()) {
            throw new GrpcException("failed to decode gRPC frame from request body");
        }

        GrpcJsonMessageConverter converter = descriptorStore.getConverter();

        if (messages.size() == 1) {
            String json = converter.toJson(messages.get(0), methodDescriptor.getInputType());
            return request
                .clone()
                .withBody(json)
                .withHeader("x-grpc-service", serviceName)
                .withHeader("x-grpc-method", methodName)
                .withHeader("x-grpc-original-content-type", request.getFirstHeader("content-type"));
        } else {
            StringBuilder jsonArray = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) {
                    jsonArray.append(",");
                }
                jsonArray.append(converter.toJson(messages.get(i), methodDescriptor.getInputType()));
            }
            jsonArray.append("]");
            return request
                .clone()
                .withBody(jsonArray.toString())
                .withHeader("x-grpc-service", serviceName)
                .withHeader("x-grpc-method", methodName)
                .withHeader("x-grpc-original-content-type", request.getFirstHeader("content-type"))
                .withHeader("x-grpc-client-streaming", "true");
        }
    }

    /**
     * Convert the response from the matching pipeline back to a gRPC-framed
     * response. Encodes the JSON body to protobuf, wraps it in a gRPC
     * length-prefixed frame, and determines the grpc-status.
     *
     * @param response        the matched HttpResponse (JSON body, grpc-status-name header)
     * @param serviceName     the gRPC service name from the original request
     * @param methodName      the gRPC method name from the original request
     * @param descriptorStore the gRPC proto descriptor store
     * @return a {@link GrpcResponseParts} with the initial headers, body bytes, and trailing headers
     */
    public static GrpcResponseParts transformGrpcResponse(
        HttpResponse response,
        String serviceName,
        String methodName,
        GrpcProtoDescriptorStore descriptorStore
    ) {
        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);

        // Determine grpc-status
        String statusName = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER);
        GrpcStatusMapper.GrpcStatusCode statusCode;
        if (statusName != null && !statusName.isEmpty()) {
            statusCode = GrpcStatusMapper.fromName(statusName);
        } else {
            statusCode = GrpcStatusMapper.GrpcStatusCode.OK;
        }

        // Encode the response body to gRPC framing
        byte[] grpcFrame = null;
        if (methodDescriptor != null) {
            String bodyString = response.getBodyAsString();
            if (bodyString != null && !bodyString.isEmpty()) {
                GrpcJsonMessageConverter converter = descriptorStore.getConverter();
                byte[] protobufBytes = converter.toProtobuf(bodyString, methodDescriptor.getOutputType());
                grpcFrame = GrpcFrameCodec.encode(protobufBytes);
            }
        }

        // Check if the response already has explicit grpc-status header (e.g., from a passthrough)
        String explicitGrpcStatus = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
        String explicitGrpcMessage = response.getFirstHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER);

        String finalStatus;
        String finalMessage;
        if (explicitGrpcStatus != null && !explicitGrpcStatus.isEmpty()) {
            finalStatus = explicitGrpcStatus;
            finalMessage = explicitGrpcMessage;
        } else {
            finalStatus = String.valueOf(statusCode.getCode());
            finalMessage = null;
        }

        return new GrpcResponseParts(grpcFrame, finalStatus, finalMessage);
    }

    /**
     * Build an error response (trailers-only pattern) for gRPC over HTTP/3.
     * Used when gRPC request processing fails before matching.
     *
     * @param statusCode the gRPC status code
     * @param message    the error message
     * @return a {@link GrpcResponseParts} with no body and the error status
     */
    public static GrpcResponseParts errorResponse(
        GrpcStatusMapper.GrpcStatusCode statusCode,
        String message
    ) {
        return new GrpcResponseParts(null, String.valueOf(statusCode.getCode()), message);
    }

    /**
     * Build the initial HTTP/3 HEADERS frame for a gRPC response.
     * Contains :status=200 and content-type=application/grpc but NOT grpc-status
     * (which belongs in the trailing HEADERS frame).
     */
    public static DefaultHttp3HeadersFrame buildInitialHeadersFrame() {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status("200");
        headersFrame.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        headersFrame.headers().add("server", "mockserver-http3");
        return headersFrame;
    }

    /**
     * Build the trailing HTTP/3 HEADERS frame for a gRPC response.
     * Contains grpc-status and optionally grpc-message. No pseudo-headers
     * (no :status) because this is a trailing HEADERS frame.
     */
    public static DefaultHttp3HeadersFrame buildTrailingHeadersFrame(String grpcStatus, String grpcMessage) {
        DefaultHttp3HeadersFrame trailersFrame = new DefaultHttp3HeadersFrame();
        trailersFrame.headers().add(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus);
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            trailersFrame.headers().add(GrpcStatusMapper.GRPC_MESSAGE_HEADER, grpcMessage);
        }
        return trailersFrame;
    }

    /**
     * Build an HTTP/3 DATA frame from gRPC-framed bytes.
     * Returns null if the frame bytes are null or empty.
     */
    public static DefaultHttp3DataFrame buildDataFrame(byte[] grpcFrameBytes) {
        if (grpcFrameBytes == null || grpcFrameBytes.length == 0) {
            return null;
        }
        return new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(grpcFrameBytes));
    }

    /**
     * Build a "trailers-only" HTTP/3 HEADERS frame that combines :status, content-type,
     * and grpc-status/grpc-message in a single HEADERS frame (no DATA frame follows).
     * This is used for error responses where there is no message body.
     */
    public static DefaultHttp3HeadersFrame buildTrailersOnlyFrame(String grpcStatus, String grpcMessage) {
        DefaultHttp3HeadersFrame frame = new DefaultHttp3HeadersFrame();
        frame.headers().status("200");
        frame.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        frame.headers().add("server", "mockserver-http3");
        frame.headers().add(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus);
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            frame.headers().add(GrpcStatusMapper.GRPC_MESSAGE_HEADER, grpcMessage);
        }
        return frame;
    }

    /**
     * Parse a gRPC path (e.g., "/package.ServiceName/MethodName") into
     * [serviceName, methodName].
     */
    static String[] parseGrpcPath(String path) {
        if (path == null || path.isEmpty()) {
            return new String[]{"", ""};
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int slashIndex = path.lastIndexOf('/');
        if (slashIndex < 1 || slashIndex == path.length() - 1) {
            return new String[]{path, ""};
        }
        return new String[]{path.substring(0, slashIndex), path.substring(slashIndex + 1)};
    }

    /**
     * Holds the parts of a gRPC response for HTTP/3 framing:
     * the gRPC-framed body bytes, the grpc-status value, and
     * an optional grpc-message.
     */
    public static final class GrpcResponseParts {
        private final byte[] grpcFrameBytes;
        private final String grpcStatus;
        private final String grpcMessage;

        public GrpcResponseParts(byte[] grpcFrameBytes, String grpcStatus, String grpcMessage) {
            this.grpcFrameBytes = grpcFrameBytes;
            this.grpcStatus = grpcStatus;
            this.grpcMessage = grpcMessage;
        }

        public byte[] grpcFrameBytes() {
            return grpcFrameBytes;
        }

        public String grpcStatus() {
            return grpcStatus;
        }

        public String grpcMessage() {
            return grpcMessage;
        }

        public boolean hasBody() {
            return grpcFrameBytes != null && grpcFrameBytes.length > 0;
        }
    }
}
