package org.mockserver.grpc.connect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Connect protocol (buf.build Connect) unary error.
 * <p>
 * Connect unary errors are returned as a non-200 HTTP response with a JSON body of the shape:
 * <pre>{@code
 * {
 *   "code": "not_found",
 *   "message": "...",
 *   "details": [ ... ]
 * }
 * }</pre>
 * where {@code code} is one of the canonical Connect error code strings. Connect error codes are the
 * lower-case snake_case forms of the gRPC status names (e.g. gRPC {@code INVALID_ARGUMENT} ->
 * Connect {@code invalid_argument}), and each maps to a specific HTTP status code per the Connect
 * specification (https://connectrpc.com/docs/protocol#error-codes).
 * <p>
 * The HTTP status mapping mirrors the Connect reference implementation's {@code codeToHTTP}
 * (connectrpc/connect-go), which is in turn consistent with {@link org.mockserver.grpc.GrpcStatusMapper}.
 * There is no Connect code for gRPC {@code OK}; a successful unary response is an HTTP 200 with the
 * message body, not an error envelope.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "message", "details"})
public class ConnectError {

    /**
     * The canonical Connect error codes. The enum constant name is the gRPC status name; {@link #code()}
     * returns the lower-case Connect wire string and {@link #httpStatus()} the mapped HTTP status.
     */
    public enum Code {
        // single-L "canceled" is the deliberate Connect/gRPC wire spelling (cf. gRPC CANCELLED, two L's)
        CANCELED("canceled", 499),
        UNKNOWN("unknown", 500),
        INVALID_ARGUMENT("invalid_argument", 400),
        DEADLINE_EXCEEDED("deadline_exceeded", 504),
        NOT_FOUND("not_found", 404),
        ALREADY_EXISTS("already_exists", 409),
        PERMISSION_DENIED("permission_denied", 403),
        RESOURCE_EXHAUSTED("resource_exhausted", 429),
        FAILED_PRECONDITION("failed_precondition", 400),
        ABORTED("aborted", 409),
        OUT_OF_RANGE("out_of_range", 400),
        UNIMPLEMENTED("unimplemented", 501),
        INTERNAL("internal", 500),
        UNAVAILABLE("unavailable", 503),
        DATA_LOSS("data_loss", 500),
        UNAUTHENTICATED("unauthenticated", 401);

        private final String code;
        private final int httpStatus;

        Code(String code, int httpStatus) {
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public String code() {
            return code;
        }

        public int httpStatus() {
            return httpStatus;
        }

        /**
         * Resolves a Connect {@link Code} from either its wire form ({@code not_found}) or the
         * equivalent gRPC status name ({@code NOT_FOUND}). Returns {@link #UNKNOWN} for null or
         * unrecognised values so error framing never fails.
         */
        public static Code fromString(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            String normalised = value.trim().toLowerCase(Locale.ROOT);
            for (Code candidate : values()) {
                if (candidate.code.equals(normalised) || candidate.name().toLowerCase(Locale.ROOT).equals(normalised)) {
                    return candidate;
                }
            }
            return UNKNOWN;
        }
    }

    @JsonProperty("code")
    private String code;
    @JsonProperty("message")
    private String message;
    @JsonProperty("details")
    private List<Object> details;

    public ConnectError() {
    }

    public ConnectError(Code code, String message) {
        this.code = code != null ? code.code() : Code.UNKNOWN.code();
        this.message = message;
    }

    public static ConnectError connectError(Code code, String message) {
        return new ConnectError(code, message);
    }

    public static ConnectError connectError(String code, String message) {
        return new ConnectError(Code.fromString(code), message);
    }

    public String getCode() {
        return code;
    }

    public ConnectError setCode(String code) {
        this.code = code;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public ConnectError setMessage(String message) {
        this.message = message;
        return this;
    }

    public List<Object> getDetails() {
        return details;
    }

    public ConnectError setDetails(List<Object> details) {
        this.details = details;
        return this;
    }

    public ConnectError addDetail(Object detail) {
        if (details == null) {
            details = new ArrayList<>();
        }
        details.add(detail);
        return this;
    }

    /**
     * The HTTP status this error maps to, resolved via {@link Code#fromString(String)} so an
     * unrecognised or null code degrades to the {@code unknown} mapping (HTTP 500).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int httpStatus() {
        return Code.fromString(code).httpStatus();
    }
}
