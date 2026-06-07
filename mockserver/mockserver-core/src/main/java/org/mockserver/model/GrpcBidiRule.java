package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Reactive rule for bidirectional gRPC streaming: each inbound client message is matched
 * against rules in order; the first rule whose {@link #matchJson} matches the inbound
 * message JSON emits its {@link #responses} as DATA frames.
 * <p>
 * Matching semantics mirror {@link WebSocketMessageMatcher#getTextMatcher()} /
 * {@code BidirectionalWebSocketFrameHandler.matches}: exact string match first,
 * then regex via {@code String.matches()}.
 */
public class GrpcBidiRule extends ObjectWithJsonToString {
    private int hashCode;
    private NottableString matchJson;
    private List<GrpcStreamMessage> responses;

    public static GrpcBidiRule grpcBidiRule() {
        return new GrpcBidiRule();
    }

    public static GrpcBidiRule grpcBidiRule(String matchJson) {
        return new GrpcBidiRule().withMatchJson(matchJson);
    }

    public NottableString getMatchJson() {
        return matchJson;
    }

    public GrpcBidiRule withMatchJson(String matchJson) {
        this.matchJson = NottableString.string(matchJson);
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiRule withMatchJson(NottableString matchJson) {
        this.matchJson = matchJson;
        this.hashCode = 0;
        return this;
    }

    public List<GrpcStreamMessage> getResponses() {
        return responses;
    }

    public GrpcBidiRule withResponses(List<GrpcStreamMessage> responses) {
        this.responses = responses;
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiRule withResponses(GrpcStreamMessage... responses) {
        this.responses = new ArrayList<>(Arrays.asList(responses));
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiRule withResponse(GrpcStreamMessage response) {
        if (this.responses == null) {
            this.responses = new ArrayList<>();
        }
        this.responses.add(response);
        this.hashCode = 0;
        return this;
    }

    public GrpcBidiRule withResponse(String json) {
        return withResponse(GrpcStreamMessage.grpcStreamMessage(json));
    }

    public GrpcBidiRule withResponse(String json, Delay delay) {
        return withResponse(GrpcStreamMessage.grpcStreamMessage(json).withDelay(delay));
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
        GrpcBidiRule that = (GrpcBidiRule) o;
        return Objects.equals(matchJson, that.matchJson) &&
            Objects.equals(responses, that.responses);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(matchJson, responses);
        }
        return hashCode;
    }
}
