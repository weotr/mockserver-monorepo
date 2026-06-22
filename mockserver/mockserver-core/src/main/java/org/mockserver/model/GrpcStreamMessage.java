package org.mockserver.model;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class GrpcStreamMessage extends ObjectWithJsonToString {
    private int hashCode;
    private String json;
    private Delay delay;
    private HttpTemplate.TemplateType templateType;

    public static GrpcStreamMessage grpcStreamMessage() {
        return new GrpcStreamMessage();
    }

    public static GrpcStreamMessage grpcStreamMessage(String json) {
        return new GrpcStreamMessage().withJson(json);
    }

    public GrpcStreamMessage withJson(String json) {
        this.json = json;
        this.hashCode = 0;
        return this;
    }

    public String getJson() {
        return json;
    }

    /**
     * Opt-in response templating: when set (to {@link HttpTemplate.TemplateType#VELOCITY} or
     * {@link HttpTemplate.TemplateType#MUSTACHE}), the {@link #getJson()} content is treated as
     * a response template rendered against the matched inbound gRPC message (exposed as the
     * request body, so {@code $!request.body}, {@code jsonPath}, the built-in helpers and the
     * {@code scenario} helper are all available) rather than emitted verbatim.
     * <p>
     * When {@code null} (the default) the response is emitted byte-for-byte unchanged, exactly
     * as before this field existed. {@link HttpTemplate.TemplateType#JAVASCRIPT} is not supported
     * for bidi stream templating (JavaScript templates construct a full response object rather
     * than a text fragment) and is rejected at render time.
     */
    public GrpcStreamMessage withTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        this.hashCode = 0;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public GrpcStreamMessage withDelay(Delay delay) {
        this.delay = delay;
        this.hashCode = 0;
        return this;
    }

    public GrpcStreamMessage withDelay(TimeUnit timeUnit, long value) {
        this.delay = new Delay(timeUnit, value);
        this.hashCode = 0;
        return this;
    }

    public Delay getDelay() {
        return delay;
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
        GrpcStreamMessage that = (GrpcStreamMessage) o;
        return Objects.equals(json, that.json) &&
            Objects.equals(delay, that.delay) &&
            templateType == that.templateType;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(json, delay, templateType);
        }
        return hashCode;
    }
}
