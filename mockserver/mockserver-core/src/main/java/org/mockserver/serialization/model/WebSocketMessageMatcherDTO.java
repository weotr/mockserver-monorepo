package org.mockserver.serialization.model;

import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.List;

public class WebSocketMessageMatcherDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<WebSocketMessageMatcher> {
    private WebSocketFrameType frameType;
    private String textMatcher;
    private List<WebSocketMessageModelDTO> responses;

    public WebSocketMessageMatcherDTO(WebSocketMessageMatcher matcher) {
        if (matcher != null) {
            frameType = matcher.getFrameType();
            if (matcher.getTextMatcher() != null) {
                textMatcher = matcher.getTextMatcher().getValue();
            }
            if (matcher.getResponses() != null) {
                responses = new ArrayList<>();
                matcher.getResponses().forEach(response -> responses.add(new WebSocketMessageModelDTO(response)));
            }
        }
    }

    public WebSocketMessageMatcherDTO() {
    }

    public WebSocketMessageMatcher buildObject() {
        WebSocketMessageMatcher matcher = new WebSocketMessageMatcher();
        if (frameType != null) {
            matcher.withFrameType(frameType);
        } else {
            matcher.withFrameType(WebSocketFrameType.ANY);
        }
        if (textMatcher != null) {
            matcher.withText(textMatcher);
        }
        if (responses != null) {
            List<WebSocketMessage> messages = new ArrayList<>();
            responses.forEach(dto -> messages.add(dto.buildObject()));
            matcher.withResponses(messages);
        }
        return matcher;
    }

    public WebSocketFrameType getFrameType() {
        return frameType;
    }

    public WebSocketMessageMatcherDTO setFrameType(WebSocketFrameType frameType) {
        this.frameType = frameType;
        return this;
    }

    public String getTextMatcher() {
        return textMatcher;
    }

    public WebSocketMessageMatcherDTO setTextMatcher(String textMatcher) {
        this.textMatcher = textMatcher;
        return this;
    }

    public List<WebSocketMessageModelDTO> getResponses() {
        return responses;
    }

    public WebSocketMessageMatcherDTO setResponses(List<WebSocketMessageModelDTO> responses) {
        this.responses = responses;
        return this;
    }
}
