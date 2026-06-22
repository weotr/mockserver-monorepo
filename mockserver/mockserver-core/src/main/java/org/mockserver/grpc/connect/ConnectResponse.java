package org.mockserver.grpc.connect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

/**
 * Convenience factory for Connect protocol (buf.build Connect) <b>unary</b> responses.
 * <p>
 * Connect unary RPCs are ordinary HTTP POSTs to {@code /package.Service/Method} carrying the request
 * message directly (JSON or proto, <i>not</i> gRPC length-prefixed framing). Because they are plain
 * HTTP, MockServer's normal expectation matching already handles them — a user can mock a Connect
 * unary call with a standard {@code httpRequest}/{@code httpResponse} expectation. These helpers exist
 * only for <b>convenience and correctness</b>:
 * <ul>
 *   <li>{@link #success(String)} sets the {@code application/json} content type expected by Connect
 *       clients on a 200 response whose body is the response message directly.</li>
 *   <li>{@link #error(ConnectError)} builds the non-200 Connect error envelope
 *       ({@code {"code","message","details"}}) with the HTTP status mapped from the Connect error
 *       code per the Connect spec.</li>
 * </ul>
 * No new action type, pipeline handler, or serialization wiring is required: the helpers return a
 * plain {@link HttpResponse}, so all existing response infrastructure (delays, headers, verification,
 * the dashboard, DTO serialization) works unchanged, and real gRPC ({@code application/grpc}) traffic
 * is completely unaffected.
 */
public class ConnectResponse {

    public static final String CONNECT_JSON_CONTENT_TYPE = "application/json";
    public static final String CONNECT_PROTO_CONTENT_TYPE = "application/proto";

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    private ConnectResponse() {
    }

    /**
     * A successful Connect unary response: HTTP 200, {@code Content-Type: application/json}, body is
     * the response message JSON directly (no envelope, no framing).
     */
    public static HttpResponse success(String messageJson) {
        return HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", CONNECT_JSON_CONTENT_TYPE)
            .withBody(messageJson == null ? "{}" : messageJson);
    }

    /**
     * A successful Connect unary response with an explicit content type, e.g.
     * {@link #CONNECT_PROTO_CONTENT_TYPE}. The body is passed through verbatim.
     */
    public static HttpResponse success(String body, String contentType) {
        return HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", contentType == null ? CONNECT_JSON_CONTENT_TYPE : contentType)
            .withBody(body == null ? "" : body);
    }

    /**
     * A Connect unary error response: a non-200 HTTP status mapped from the Connect error code, with a
     * JSON body of the shape {@code {"code","message","details"}} and {@code Content-Type:
     * application/json}.
     */
    public static HttpResponse error(ConnectError error) {
        ConnectError safe = error != null ? error : new ConnectError(ConnectError.Code.UNKNOWN, null);
        return HttpResponse.response()
            .withStatusCode(safe.httpStatus())
            .withHeader("content-type", CONNECT_JSON_CONTENT_TYPE)
            .withBody(serialise(safe));
    }

    /**
     * Shorthand for {@link #error(ConnectError)} from a {@link ConnectError.Code} and message.
     */
    public static HttpResponse error(ConnectError.Code code, String message) {
        return error(new ConnectError(code, message));
    }

    private static String serialise(ConnectError error) {
        try {
            return OBJECT_MAPPER.writeValueAsString(error);
        } catch (JsonProcessingException e) {
            // Fall back to a hand-built envelope so error framing never throws. Use Jackson to encode
            // each string value so backslashes and control characters are escaped (a hand-rolled
            // quote-only replace would emit structurally invalid JSON for e.g. a Windows path).
            String code = error.getCode() != null ? error.getCode() : ConnectError.Code.UNKNOWN.code();
            String message = error.getMessage() != null ? error.getMessage() : "";
            try {
                return "{\"code\":" + OBJECT_MAPPER.writeValueAsString(code)
                    + ",\"message\":" + OBJECT_MAPPER.writeValueAsString(message) + "}";
            } catch (JsonProcessingException unreachable) {
                return "{\"code\":\"unknown\",\"message\":\"\"}";
            }
        }
    }
}
