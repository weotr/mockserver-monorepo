package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.Headers;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

public class GrpcBidiResponseDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<GrpcBidiResponse> {
    private DelayDTO delay;
    private String statusName;
    private String statusMessage;
    private Headers headers;
    private List<GrpcStreamMessageDTO> messages;
    private List<GrpcBidiRuleDTO> rules;
    private Boolean closeConnection;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean primary;

    public GrpcBidiResponseDTO(GrpcBidiResponse grpcBidiResponse) {
        if (grpcBidiResponse != null) {
            if (grpcBidiResponse.getDelay() != null) {
                delay = new DelayDTO(grpcBidiResponse.getDelay());
            }
            statusName = grpcBidiResponse.getStatusName();
            statusMessage = grpcBidiResponse.getStatusMessage();
            headers = grpcBidiResponse.getHeaders();
            closeConnection = grpcBidiResponse.getCloseConnection();
            if (grpcBidiResponse.getMessages() != null) {
                messages = new ArrayList<>();
                grpcBidiResponse.getMessages().forEach(msg -> messages.add(new GrpcStreamMessageDTO(msg)));
            }
            if (grpcBidiResponse.getRules() != null) {
                rules = new ArrayList<>();
                grpcBidiResponse.getRules().forEach(rule -> rules.add(new GrpcBidiRuleDTO(rule)));
            }
            primary = grpcBidiResponse.isPrimary();
        }
    }

    public GrpcBidiResponseDTO() {
    }

    public GrpcBidiResponse buildObject() {
        GrpcBidiResponse grpcBidiResponse = new GrpcBidiResponse()
            .withDelay(delay != null ? delay.buildObject() : null)
            .withStatusName(statusName)
            .withStatusMessage(statusMessage)
            .withHeaders(headers)
            .withCloseConnection(closeConnection)
            .withPrimary(primary);
        if (messages != null) {
            messages.forEach(msgDTO -> grpcBidiResponse.withMessage(msgDTO.buildObject()));
        }
        if (rules != null) {
            rules.forEach(ruleDTO -> grpcBidiResponse.withRule(ruleDTO.buildObject()));
        }
        return grpcBidiResponse;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public GrpcBidiResponseDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public String getStatusName() {
        return statusName;
    }

    public GrpcBidiResponseDTO setStatusName(String statusName) {
        this.statusName = statusName;
        return this;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public GrpcBidiResponseDTO setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        return this;
    }

    public Headers getHeaders() {
        return headers;
    }

    public GrpcBidiResponseDTO setHeaders(Headers headers) {
        this.headers = headers;
        return this;
    }

    public List<GrpcStreamMessageDTO> getMessages() {
        return messages;
    }

    public GrpcBidiResponseDTO setMessages(List<GrpcStreamMessageDTO> messages) {
        this.messages = messages;
        return this;
    }

    public List<GrpcBidiRuleDTO> getRules() {
        return rules;
    }

    public GrpcBidiResponseDTO setRules(List<GrpcBidiRuleDTO> rules) {
        this.rules = rules;
        return this;
    }

    public Boolean getCloseConnection() {
        return closeConnection;
    }

    public GrpcBidiResponseDTO setCloseConnection(Boolean closeConnection) {
        this.closeConnection = closeConnection;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    public GrpcBidiResponseDTO setPrimary(boolean primary) {
        this.primary = primary;
        return this;
    }
}
