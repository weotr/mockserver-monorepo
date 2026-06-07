package org.mockserver.grpc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Translates between gRPC-Web framing and standard gRPC framing.
 * <p>
 * gRPC-Web uses the same length-prefixed message framing as gRPC but differs in two ways:
 * <ul>
 *   <li><strong>Trailers-in-body:</strong> gRPC status trailers are sent as a special frame
 *       with flag byte {@code 0x80} followed by ASCII-encoded trailer lines
 *       ({@code grpc-status: N\r\ngrpc-message: ...\r\n})</li>
 *   <li><strong>Base64 encoding ({@code -text} variant):</strong> when the content-type is
 *       {@code application/grpc-web-text}, the entire body (message frames + trailer frame)
 *       is base64-encoded</li>
 * </ul>
 * <p>
 * This class provides the conversion layer so gRPC-Web requests can be fed into the
 * existing gRPC pipeline unchanged and gRPC responses can be re-framed as gRPC-Web.
 */
public class GrpcWebTranslator {

    public static final String GRPC_WEB_CONTENT_TYPE = "application/grpc-web";
    public static final String GRPC_WEB_PROTO_CONTENT_TYPE = "application/grpc-web+proto";
    public static final String GRPC_WEB_TEXT_CONTENT_TYPE = "application/grpc-web-text";
    public static final String GRPC_WEB_TEXT_PROTO_CONTENT_TYPE = "application/grpc-web-text+proto";

    /**
     * The flag byte that marks a trailer frame in gRPC-Web.
     */
    public static final byte TRAILER_FLAG = (byte) 0x80;
    private static final int HEADER_LENGTH = 5;

    /**
     * Returns {@code true} if the content-type indicates a gRPC-Web request
     * (binary or text variant).
     */
    public static boolean isGrpcWebContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        return contentType.startsWith(GRPC_WEB_CONTENT_TYPE)
            || contentType.startsWith(GRPC_WEB_TEXT_CONTENT_TYPE);
    }

    /**
     * Returns {@code true} if the content-type is the base64-encoded text variant.
     */
    public static boolean isGrpcWebTextContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        return contentType.startsWith(GRPC_WEB_TEXT_CONTENT_TYPE);
    }

    /**
     * Decodes the request body from gRPC-Web framing to standard gRPC framing.
     * For the {@code -text} variant, the body is base64-decoded first.
     * The returned bytes are standard gRPC length-prefixed message frames
     * that can be fed directly into {@link GrpcFrameCodec#decode(byte[])}.
     *
     * @param body        the raw request body
     * @param contentType the content-type header value
     * @return standard gRPC-framed message bytes
     */
    public static byte[] decodeRequestBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return body;
        }
        if (isGrpcWebTextContentType(contentType)) {
            return Base64.getDecoder().decode(body);
        }
        // binary gRPC-Web uses the same framing as standard gRPC for the request
        return body;
    }

    /**
     * Builds a gRPC-Web response body from a standard gRPC response.
     * <p>
     * The response consists of message frame(s) followed by a trailer frame
     * (flag {@code 0x80}) containing the {@code grpc-status} and optional
     * {@code grpc-message} as ASCII lines.
     *
     * @param messageFrameBody the gRPC-framed message body (may be empty/null)
     * @param grpcStatus       the gRPC status code as a string (e.g. "0")
     * @param grpcMessage      the gRPC status message (may be null)
     * @param isTextVariant    if true, the entire body is base64-encoded
     * @return the gRPC-Web response body bytes
     */
    public static byte[] encodeResponseBody(byte[] messageFrameBody, String grpcStatus, String grpcMessage, boolean isTextVariant) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // write message frame(s) as-is (they use the same framing as standard gRPC)
        if (messageFrameBody != null && messageFrameBody.length > 0) {
            bos.write(messageFrameBody, 0, messageFrameBody.length);
        }
        // build trailer frame
        byte[] trailerFrame = buildTrailerFrame(grpcStatus, grpcMessage);
        bos.write(trailerFrame, 0, trailerFrame.length);

        byte[] result = bos.toByteArray();
        if (isTextVariant) {
            return Base64.getEncoder().encode(result);
        }
        return result;
    }

    /**
     * Builds a gRPC-Web trailer frame: flag byte {@code 0x80}, 4-byte length,
     * then ASCII-encoded trailer lines.
     */
    public static byte[] buildTrailerFrame(String grpcStatus, String grpcMessage) {
        StringBuilder trailers = new StringBuilder();
        trailers.append(GrpcStatusMapper.GRPC_STATUS_HEADER).append(": ");
        trailers.append(grpcStatus != null ? grpcStatus : "0");
        trailers.append("\r\n");
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            trailers.append(GrpcStatusMapper.GRPC_MESSAGE_HEADER).append(": ");
            trailers.append(grpcMessage);
            trailers.append("\r\n");
        }
        byte[] trailerBytes = trailers.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + trailerBytes.length);
        buffer.put(TRAILER_FLAG);
        buffer.putInt(trailerBytes.length);
        buffer.put(trailerBytes);
        return buffer.array();
    }

    /**
     * Parses trailers from a gRPC-Web trailer frame body.
     * The trailer frame body is the ASCII text after the 5-byte header.
     *
     * @param trailerBody the raw trailer body bytes (without the 5-byte frame header)
     * @return parsed trailer lines as a string
     */
    public static String parseTrailerFrame(byte[] trailerBody) {
        return new String(trailerBody, StandardCharsets.US_ASCII);
    }

    /**
     * Returns the appropriate gRPC-Web response content-type based on the
     * original request content-type.
     *
     * @param requestContentType the original gRPC-Web request content-type
     * @return the matching gRPC-Web response content-type
     */
    public static String responseContentType(String requestContentType) {
        if (isGrpcWebTextContentType(requestContentType)) {
            return GRPC_WEB_TEXT_CONTENT_TYPE;
        }
        return GRPC_WEB_CONTENT_TYPE;
    }
}
