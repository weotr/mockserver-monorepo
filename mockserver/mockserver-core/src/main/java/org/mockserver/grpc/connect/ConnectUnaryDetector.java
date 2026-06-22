package org.mockserver.grpc.connect;

import com.google.protobuf.Descriptors;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;

/**
 * Detects Connect protocol (buf.build Connect) <b>unary</b> requests and, when a proto descriptor is
 * loaded, validates the request message JSON against the method's input type.
 * <p>
 * Connect unary requests are distinguished from gRPC by content type and shape:
 * <ul>
 *   <li>gRPC uses {@code application/grpc} (and {@code application/grpc-web*}) with length-prefixed
 *       framing over HTTP/2 — handled by the existing gRPC pipeline.</li>
 *   <li>Connect unary is a plain HTTP {@code POST} to a {@code /package.Service/Method}-shaped path
 *       with {@code Content-Type: application/json} (or {@code application/proto}) and the request
 *       message as the body directly (no framing).</li>
 * </ul>
 * Detection is deliberately conservative: it never matches an {@code application/grpc*} content type,
 * so real gRPC traffic can never be misclassified as Connect.
 * <p>
 * This is a pure helper. Connect unary requests are already ordinary HTTP requests that flow through
 * MockServer's normal expectation matching; this class only supports optional descriptor-aware
 * validation/conversion of the request body.
 */
public class ConnectUnaryDetector {

    private ConnectUnaryDetector() {
    }

    /**
     * Returns true if the given method/content-type/path looks like a Connect unary request:
     * a {@code POST} to a two-segment {@code /Service/Method} path with a JSON or proto content type
     * that is <b>not</b> a gRPC content type.
     */
    public static boolean isConnectUnary(String method, String contentType, String path) {
        if (method == null || !"POST".equalsIgnoreCase(method)) {
            return false;
        }
        if (GrpcStatusMapper.isGrpcContentType(contentType) || isGrpcWebContentType(contentType)) {
            return false;
        }
        if (!isConnectContentType(contentType)) {
            return false;
        }
        return isServiceMethodPath(path);
    }

    /**
     * A Connect unary content type is JSON or proto. (The Connect "GET-side" unary variant and
     * compressed/streaming content types are deliberately out of scope.)
     */
    public static boolean isConnectContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith(ConnectResponse.CONNECT_JSON_CONTENT_TYPE)
            || lower.startsWith(ConnectResponse.CONNECT_PROTO_CONTENT_TYPE);
    }

    private static boolean isGrpcWebContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/grpc-web");
    }

    /**
     * A gRPC/Connect method path has the shape {@code /package.Service/Method} — exactly two non-empty
     * segments where the service segment contains a {@code .} package separator.
     */
    public static boolean isServiceMethodPath(String path) {
        if (path == null || path.length() < 3) {
            return false;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.lastIndexOf('/');
        if (slash < 1 || slash == trimmed.length() - 1) {
            return false;
        }
        String service = trimmed.substring(0, slash);
        String methodName = trimmed.substring(slash + 1);
        // service must not itself contain a further '/', and should look like a package-qualified name
        return service.indexOf('/') < 0 && service.indexOf('.') > 0 && !methodName.isEmpty();
    }

    /**
     * Splits a {@code /package.Service/Method} path into {@code [service, method]}. Returns
     * {@code ["", ""]} for a malformed path.
     */
    public static String[] parseServiceMethod(String path) {
        if (!isServiceMethodPath(path)) {
            return new String[]{"", ""};
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.lastIndexOf('/');
        return new String[]{trimmed.substring(0, slash), trimmed.substring(slash + 1)};
    }

    /**
     * If a descriptor is loaded for this method, validates that the request JSON parses against the
     * method's input message type. Returns {@code null} when valid (or when no descriptor is loaded —
     * validation is best-effort and never blocks a plain-HTTP mock), or a {@link ConnectError} with
     * code {@code invalid_argument} describing the parse failure.
     */
    public static ConnectError validateRequestBody(GrpcProtoDescriptorStore descriptorStore, String path, String json) {
        if (descriptorStore == null || !descriptorStore.hasServices() || json == null) {
            return null;
        }
        String[] parts = parseServiceMethod(path);
        Descriptors.MethodDescriptor method = descriptorStore.getMethod(parts[0], parts[1]);
        if (method == null) {
            return null;
        }
        GrpcJsonMessageConverter converter = descriptorStore.getConverter();
        if (converter == null) {
            return null;
        }
        try {
            converter.toProtobuf(json, method.getInputType());
            return null;
        } catch (Exception e) {
            return new ConnectError(ConnectError.Code.INVALID_ARGUMENT,
                "request body does not match " + method.getInputType().getFullName() + ": " + e.getMessage());
        }
    }
}
