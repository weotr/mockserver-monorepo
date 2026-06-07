package org.mockserver.serialization.model;

import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

public class GrpcBidiRuleDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<GrpcBidiRule> {
    private String matchJson;
    private List<GrpcStreamMessageDTO> responses;

    public GrpcBidiRuleDTO(GrpcBidiRule rule) {
        if (rule != null) {
            if (rule.getMatchJson() != null) {
                matchJson = rule.getMatchJson().getValue();
            }
            if (rule.getResponses() != null) {
                responses = new ArrayList<>();
                rule.getResponses().forEach(msg -> responses.add(new GrpcStreamMessageDTO(msg)));
            }
        }
    }

    public GrpcBidiRuleDTO() {
    }

    public GrpcBidiRule buildObject() {
        GrpcBidiRule rule = new GrpcBidiRule();
        if (matchJson != null) {
            rule.withMatchJson(matchJson);
        }
        if (responses != null) {
            responses.forEach(msgDTO -> rule.withResponse(msgDTO.buildObject()));
        }
        return rule;
    }

    public String getMatchJson() {
        return matchJson;
    }

    public GrpcBidiRuleDTO setMatchJson(String matchJson) {
        this.matchJson = matchJson;
        return this;
    }

    public List<GrpcStreamMessageDTO> getResponses() {
        return responses;
    }

    public GrpcBidiRuleDTO setResponses(List<GrpcStreamMessageDTO> responses) {
        this.responses = responses;
        return this;
    }
}
