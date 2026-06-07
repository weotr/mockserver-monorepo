package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

/**
 * Response action for true bidirectional gRPC streaming. Expresses reactive bidi behaviour
 * declaratively, mirroring the WebSocket {@link HttpWebSocketResponse} pattern (eager
 * messages + first-match rule list) adapted for gRPC.
 * <p>
 * Fields:
 * <ul>
 *   <li>{@link #headers} — initial response headers (sent with the :status 200 HEADERS frame)</li>
 *   <li>{@link #messages} — EAGER server-push messages sent immediately after the initial HEADERS,
 *       regardless of inbound messages. Per-message {@link GrpcStreamMessage#getDelay()} is
 *       honoured via event-loop scheduling: each message is written after its configured delay
 *       elapses, chained sequentially so ordering is preserved. The top-level action
 *       {@link #getDelay()} (inherited from {@link Action}) is applied before the initial
 *       HEADERS frame is written.</li>
 *   <li>{@link #rules} — reactive rules: each inbound client message is matched against rules
 *       in order; the first rule whose matchJson matches emits its responses</li>
 *   <li>{@link #statusName}, {@link #statusMessage} — final grpc-status trailer (default OK/0)</li>
 *   <li>{@link #closeConnection} — whether to close the connection after the trailing status</li>
 * </ul>
 */
public class GrpcBidiResponse extends Action<GrpcBidiResponse> {
    private int hashCode;
    private String statusName;
    private String statusMessage;
    private Headers headers;
    private List<GrpcStreamMessage> messages;
    private List<GrpcBidiRule> rules;
    private Boolean closeConnection;

    public static GrpcBidiResponse grpcBidiResponse() {
        return new GrpcBidiResponse();
    }

    public GrpcBidiResponse withStatusName(String statusName) {
        this.statusName = statusName;
        this.hashCode = 0;
        return this;
    }

    public String getStatusName() {
        return statusName;
    }

    public GrpcBidiResponse withStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        this.hashCode = 0;
        return this;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public GrpcBidiResponse withHeaders(Headers headers) {
        this.headers = headers;
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withHeader(Header header) {
        if (this.headers == null) {
            this.headers = new Headers();
        }
        this.headers.withEntry(header);
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withHeader(String name, String... values) {
        if (this.headers == null) {
            this.headers = new Headers();
        }
        this.headers.withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    public Headers getHeaders() {
        return headers;
    }

    public GrpcBidiResponse withMessages(List<GrpcStreamMessage> messages) {
        this.messages = messages;
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withMessages(GrpcStreamMessage... messages) {
        this.messages = Arrays.asList(messages);
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withMessage(GrpcStreamMessage message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withMessage(String json) {
        return withMessage(GrpcStreamMessage.grpcStreamMessage(json));
    }

    public GrpcBidiResponse withMessage(String json, Delay delay) {
        return withMessage(GrpcStreamMessage.grpcStreamMessage(json).withDelay(delay));
    }

    public List<GrpcStreamMessage> getMessages() {
        return messages;
    }

    public GrpcBidiResponse withRules(List<GrpcBidiRule> rules) {
        this.rules = rules;
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withRules(GrpcBidiRule... rules) {
        this.rules = new ArrayList<>(Arrays.asList(rules));
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiResponse withRule(GrpcBidiRule rule) {
        if (this.rules == null) {
            this.rules = new ArrayList<>();
        }
        this.rules.add(rule);
        this.hashCode = 0;
        return this;
    }

    public List<GrpcBidiRule> getRules() {
        return rules;
    }

    public GrpcBidiResponse withCloseConnection(Boolean closeConnection) {
        this.closeConnection = closeConnection;
        this.hashCode = 0;
        return this;
    }

    public Boolean getCloseConnection() {
        return closeConnection;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.GRPC_BIDI_RESPONSE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        GrpcBidiResponse that = (GrpcBidiResponse) o;
        return Objects.equals(statusName, that.statusName) &&
            Objects.equals(statusMessage, that.statusMessage) &&
            Objects.equals(headers, that.headers) &&
            Objects.equals(messages, that.messages) &&
            Objects.equals(rules, that.rules) &&
            Objects.equals(closeConnection, that.closeConnection);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), statusName, statusMessage, headers, messages, rules, closeConnection);
        }
        return hashCode;
    }
}
