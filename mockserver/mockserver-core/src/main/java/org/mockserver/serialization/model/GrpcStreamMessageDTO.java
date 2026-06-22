package org.mockserver.serialization.model;

import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class GrpcStreamMessageDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<GrpcStreamMessage> {
    private String json;
    private DelayDTO delay;
    private HttpTemplate.TemplateType templateType;

    public GrpcStreamMessageDTO(GrpcStreamMessage grpcStreamMessage) {
        if (grpcStreamMessage != null) {
            json = grpcStreamMessage.getJson();
            if (grpcStreamMessage.getDelay() != null) {
                delay = new DelayDTO(grpcStreamMessage.getDelay());
            }
            templateType = grpcStreamMessage.getTemplateType();
        }
    }

    public GrpcStreamMessageDTO() {
    }

    public GrpcStreamMessage buildObject() {
        GrpcStreamMessage grpcStreamMessage = new GrpcStreamMessage();
        grpcStreamMessage.withJson(json);
        if (delay != null) {
            grpcStreamMessage.withDelay(delay.buildObject());
        }
        if (templateType != null) {
            grpcStreamMessage.withTemplateType(templateType);
        }
        return grpcStreamMessage;
    }

    public String getJson() {
        return json;
    }

    public GrpcStreamMessageDTO setJson(String json) {
        this.json = json;
        return this;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public GrpcStreamMessageDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public GrpcStreamMessageDTO setTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }
}
